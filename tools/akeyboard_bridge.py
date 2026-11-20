"""Standalone Windows 10/11 Bluetooth RFCOMM receiver for AKeyboard Voice Bridge.

Uses only Python 3.10+ standard library (socket, select, re, json, ctypes, threading).
"""

from __future__ import annotations

import ctypes
from ctypes import wintypes
import json
import re
import select
import socket
import sys
import threading
import uuid

BRIDGE_UUID = uuid.UUID("8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b")
MAX_LINE_BYTES = 64 * 1024
UNICODE_CHUNK_UNITS = 32

AF_BTH = 32
NS_BTH = 16
BTHPROTO_RFCOMM = 3
BT_PORT_ANY = 0xFFFFFFFF
RNRSERVICE_REGISTER = 0
RNRSERVICE_DELETE = 2

INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
VK_BACK = 0x08
VK_RETURN = 0x0D
MAPVK_VK_TO_VSC = 0

CTRL_C_EVENT = 0
CTRL_BREAK_EVENT = 1
CTRL_CLOSE_EVENT = 2
CTRL_LOGOFF_EVENT = 5
CTRL_SHUTDOWN_EVENT = 6

BACKOFF_DELAYS_SECONDS = (2, 5, 10)
SELECT_TIMEOUT_SECONDS = 0.5
CONSOLE_CLEANUP_WAIT_SECONDS = 3.5

_PEER_DISCONNECT_WINERRORS = frozenset({10054, 10053, 10058, 10060})

ULONG_PTR = ctypes.c_size_t


class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", ctypes.c_ubyte * 8),
    ]

    @classmethod
    def from_uuid(cls, value: uuid.UUID) -> GUID:
        b = value.bytes_le
        d1, d2, d3 = int.from_bytes(b[0:4], "little"), int.from_bytes(b[4:6], "little"), int.from_bytes(b[6:8], "little")
        d4 = (ctypes.c_ubyte * 8)(*b[8:16])
        return cls(d1, d2, d3, d4)


class SOCKADDR_BTH(ctypes.Structure):
    _pack_ = 1
    _fields_ = [
        ("addressFamily", ctypes.c_ushort),
        ("btAddr", ctypes.c_uint64),
        ("serviceClassId", GUID),
        ("port", wintypes.ULONG),
    ]


class SOCKET_ADDRESS(ctypes.Structure):
    _fields_ = [
        ("lpSockaddr", ctypes.c_void_p),
        ("iSockaddrLength", ctypes.c_int),
    ]


class CSADDR_INFO(ctypes.Structure):
    _fields_ = [
        ("LocalAddr", SOCKET_ADDRESS),
        ("RemoteAddr", SOCKET_ADDRESS),
        ("iSocketType", ctypes.c_int),
        ("iProtocol", ctypes.c_int),
    ]


class BLOB(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.ULONG),
        ("pBlobData", ctypes.c_void_p),
    ]


class WSAQUERYSETW(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("lpszServiceInstanceName", wintypes.LPWSTR),
        ("lpServiceClassId", ctypes.POINTER(GUID)),
        ("lpVersion", ctypes.c_void_p),
        ("lpszComment", wintypes.LPWSTR),
        ("dwNameSpace", wintypes.DWORD),
        ("lpNSProviderId", ctypes.POINTER(GUID)),
        ("lpszContext", wintypes.LPWSTR),
        ("dwNumberOfProtocols", wintypes.DWORD),
        ("lpafpProtocols", ctypes.c_void_p),
        ("lpszQueryString", wintypes.LPWSTR),
        ("dwNumberOfCsAddrs", wintypes.DWORD),
        ("lpcsaBuffer", ctypes.POINTER(CSADDR_INFO)),
        ("dwOutputFlags", wintypes.DWORD),
        ("lpBlob", ctypes.POINTER(BLOB)),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", wintypes.DWORD),
        ("union", _INPUT_UNION),
    ]


_stop_event = threading.Event()
_cleanup_done = threading.Event()


def _handle_console_ctrl(ctrl_type: int) -> bool:
    """Coordinate clean SDP shutdown on Windows console close/logoff/shutdown."""
    if ctrl_type == CTRL_C_EVENT:
        return False
    if ctrl_type in (CTRL_CLOSE_EVENT, CTRL_LOGOFF_EVENT, CTRL_SHUTDOWN_EVENT):
        _stop_event.set()
        _cleanup_done.wait(CONSOLE_CLEANUP_WAIT_SECONDS)
        return True
    return False


if sys.platform == "win32":
    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
    _user32.SendInput.restype = wintypes.UINT
    _user32.MapVirtualKeyW.argtypes = [wintypes.UINT, wintypes.UINT]
    _user32.MapVirtualKeyW.restype = wintypes.UINT
    _user32.GetForegroundWindow.argtypes = []
    _user32.GetForegroundWindow.restype = wintypes.HWND

    _ws2_32 = ctypes.WinDLL("ws2_32", use_last_error=True)
    _ws2_32.WSASetServiceW.argtypes = [ctypes.POINTER(WSAQUERYSETW), ctypes.c_int, wintypes.DWORD]
    _ws2_32.WSASetServiceW.restype = ctypes.c_int
    _ws2_32.WSAGetLastError.argtypes = []
    _ws2_32.WSAGetLastError.restype = ctypes.c_int

    _CONSOLE_CTRL_HANDLER_TYPE = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.DWORD)
    # Pin callback at module scope so GC cannot collect the WINFUNCTYPE trampoline.
    _CONSOLE_CTRL_HANDLER = _CONSOLE_CTRL_HANDLER_TYPE(_handle_console_ctrl)
    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _kernel32.SetConsoleCtrlHandler.argtypes = [_CONSOLE_CTRL_HANDLER_TYPE, wintypes.BOOL]
    _kernel32.SetConsoleCtrlHandler.restype = wintypes.BOOL
else:
    _user32 = None
    _ws2_32 = None
    _CONSOLE_CTRL_HANDLER_TYPE = None
    _CONSOLE_CTRL_HANDLER = None
    _kernel32 = None


def check_environment(
    platform: str = sys.platform,
    version_info: tuple[int, ...] = sys.version_info,
    has_af_bluetooth: bool | None = None,
    has_btproto_rfcomm: bool | None = None,
) -> tuple[bool, str]:
    """Validate Windows 10/11 CPython 3.10+ and Bluetooth RFCOMM socket support."""
    if has_af_bluetooth is None:
        has_af_bluetooth = hasattr(socket, "AF_BLUETOOTH")
    if has_btproto_rfcomm is None:
        has_btproto_rfcomm = hasattr(socket, "BTPROTO_RFCOMM")

    if platform != "win32":
        return (
            False,
            f"Unsupported platform '{platform}': AKeyboard bridge requires Windows 10/11 (win32) with official CPython 3.10+.",
        )
    if tuple(version_info[:2]) < (3, 10):
        return (
            False,
            f"Unsupported Python version {version_info[0]}.{version_info[1]}: Python 3.10+ is required (install official CPython 3.10+ from python.org).",
        )

    missing = []
    if not has_af_bluetooth:
        missing.append("socket.AF_BLUETOOTH")
    if not has_btproto_rfcomm:
        missing.append("socket.BTPROTO_RFCOMM")
    if missing:
        return (
            False,
            f"Python build lacks Bluetooth RFCOMM support (missing {', '.join(missing)}). "
            "Install official Windows CPython 3.10+ from python.org (Store/MSYS2/Conda/embedded builds may omit Bluetooth sockets).",
        )

    return True, ""


def _configure_console_encoding() -> None:
    """Best-effort UTF-8 console stream setup so Unicode logs never crash."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            try:
                reconfigure(encoding="utf-8", errors="replace")
            except (OSError, ValueError):
                pass


def _default_send_input(inputs: list[INPUT]) -> int:
    if _user32 is None:
        raise RuntimeError("SendInput is only supported on Windows")
    if not inputs:
        return 0
    arr = (INPUT * len(inputs))(*inputs)
    return int(_user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT)))


def _default_map_vk(vk: int) -> int:
    if _user32 is None:
        return 0
    return int(_user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC))


def _default_has_focus() -> bool:
    if _user32 is None:
        return False
    # c_void_p restype returns None for NULL HWND and int for a valid HWND.
    return (_user32.GetForegroundWindow() or 0) != 0


def _make_key_input(vk: int = 0, scan: int = 0, flags: int = 0) -> INPUT:
    return INPUT(
        type=INPUT_KEYBOARD,
        union=_INPUT_UNION(ki=KEYBDINPUT(wVk=vk, wScan=scan, dwFlags=flags, time=0, dwExtraInfo=0)),
    )


def build_virtual_key_inputs(vk: int, map_vk_fn=_default_map_vk) -> list[INPUT]:
    scan = int(map_vk_fn(vk))
    return [
        _make_key_input(vk=vk, scan=scan, flags=0),
        _make_key_input(vk=vk, scan=scan, flags=KEYEVENTF_KEYUP),
    ]


def chunk_utf16_units(text: str, max_units: int = UNICODE_CHUNK_UNITS) -> list[list[int]]:
    raw = text.encode("utf-16-le")
    units = [int.from_bytes(raw[i : i + 2], "little") for i in range(0, len(raw), 2)]
    chunks: list[list[int]] = []
    idx = 0
    while idx < len(units):
        end = min(idx + max_units, len(units))
        # Avoid splitting a UTF-16 surrogate pair across SendInput batches.
        if end < len(units) and 0xD800 <= units[end - 1] <= 0xDBFF:
            if end - idx > 1:
                end -= 1
            else:
                end += 1
        chunks.append(units[idx:end])
        idx = end
    return chunks


def send_virtual_key(
    vk: int,
    send_input_fn=_default_send_input,
    map_vk_fn=_default_map_vk,
) -> str | None:
    inputs = build_virtual_key_inputs(vk, map_vk_fn=map_vk_fn)
    sent = int(send_input_fn(inputs))
    if sent == len(inputs):
        return None
    if sent == 0:
        return "uipi"
    return "send_failed"


_EDGE_LINE_BREAK_RE = re.compile(
    r"^(?:[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*)+|(?:[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*)+$"
)
_INNER_LINE_BREAK_RE = re.compile(r"[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*")


def sanitize_unicode_text(text: str) -> str:
    cleaned = _EDGE_LINE_BREAK_RE.sub("", text)
    return _INNER_LINE_BREAK_RE.sub(" ", cleaned)


def send_unicode_text(
    text: str,
    send_input_fn=_default_send_input,
    max_units: int = UNICODE_CHUNK_UNITS,
) -> str | None:
    cleaned = sanitize_unicode_text(text)
    if not cleaned:
        return None
    for chunk in chunk_utf16_units(cleaned, max_units=max_units):
        inputs: list[INPUT] = []
        for unit in chunk:
            inputs.append(_make_key_input(vk=0, scan=unit, flags=KEYEVENTF_UNICODE))
            inputs.append(_make_key_input(vk=0, scan=unit, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        sent = int(send_input_fn(inputs))
        if sent != len(inputs):
            return "uipi" if sent == 0 else "send_failed"
    return None


def process_line(
    line: str,
    send_input_fn=_default_send_input,
    has_focus_fn=_default_has_focus,
    map_vk_fn=_default_map_vk,
) -> dict[str, object] | None:
    stripped = line.strip()
    if not stripped:
        return None
    try:
        payload = json.loads(stripped)
    except json.JSONDecodeError:
        return {"ok": False, "err": "bad_json"}

    if not isinstance(payload, dict):
        return {"ok": False, "err": "bad_payload"}

    msg_type = payload.get("type")
    if msg_type == "text":
        value = payload.get("value")
        if not isinstance(value, str):
            return {"ok": False, "err": "bad_value"}
        if not has_focus_fn():
            return {"ok": False, "err": "no_focus"}
        err = send_unicode_text(value, send_input_fn=send_input_fn)
        return {"ok": True} if err is None else {"ok": False, "err": err}

    if msg_type == "key":
        key = payload.get("key")
        vk_map = {"ENTER": VK_RETURN, "BACKSPACE": VK_BACK}
        vk = vk_map.get(key) if isinstance(key, str) else None
        if vk is None:
            return {"ok": False, "err": "bad_key"}
        if not has_focus_fn():
            return {"ok": False, "err": "no_focus"}
        err = send_virtual_key(vk, send_input_fn=send_input_fn, map_vk_fn=map_vk_fn)
        return {"ok": True} if err is None else {"ok": False, "err": err}

    return {"ok": False, "err": "unknown_type"}


def _set_sdp_service(port: int, operation: int) -> tuple[bool, int]:
    if _ws2_32 is None:
        return False, -1
    service_guid = GUID.from_uuid(BRIDGE_UUID)
    sockaddr = SOCKADDR_BTH(
        addressFamily=AF_BTH,
        btAddr=0,
        serviceClassId=service_guid,
        port=port,
    )
    csaddr = CSADDR_INFO(
        LocalAddr=SOCKET_ADDRESS(ctypes.addressof(sockaddr), ctypes.sizeof(sockaddr)),
        RemoteAddr=SOCKET_ADDRESS(None, 0),
        iSocketType=socket.SOCK_STREAM,
        iProtocol=BTHPROTO_RFCOMM,
    )
    queryset = WSAQUERYSETW(
        dwSize=ctypes.sizeof(WSAQUERYSETW),
        lpszServiceInstanceName="AKeyboard Voice Bridge",
        lpServiceClassId=ctypes.pointer(service_guid),
        lpVersion=None,
        lpszComment="AKeyboard Bluetooth RFCOMM Bridge",
        dwNameSpace=NS_BTH,
        lpNSProviderId=None,
        lpszContext=None,
        dwNumberOfProtocols=0,
        lpafpProtocols=None,
        lpszQueryString=None,
        dwNumberOfCsAddrs=1,
        lpcsaBuffer=ctypes.pointer(csaddr),
        dwOutputFlags=0,
        lpBlob=None,
    )
    res = _ws2_32.WSASetServiceW(ctypes.byref(queryset), operation, 0)
    if res != 0:
        return False, int(_ws2_32.WSAGetLastError())
    return True, 0


class _SdpRegistrationError(OSError):
    """Raised when WSASetServiceW fails so outer loop tears down and retries."""

    def __init__(self, wsa_error: int, port: int) -> None:
        self.wsa_error = wsa_error
        self.winerror = wsa_error
        self.port = port
        super().__init__(f"WSASetServiceW failed (WSAError={wsa_error}) on RFCOMM channel {port}")


def classify_winsock_error(winerror: int | None, detail: object = "") -> str:
    """Map Winsock error codes to actionable Bluetooth/policy guidance."""
    if winerror == 10047:
        return (
            "Bluetooth RFCOMM protocol is unavailable (winerror=10047, WSAEAFNOSUPPORT). "
            "Check that a Bluetooth adapter is enabled in Device Manager and Windows Bluetooth Support Service is running."
        )
    if winerror == 10050:
        return (
            "Bluetooth adapter is down or turned off (winerror=10050, WSAENETDOWN). "
            "Turn on Bluetooth in Windows Settings and ensure Airplane mode is off."
        )
    if winerror == 10049:
        return (
            "Local Bluetooth adapter address is unavailable (winerror=10049, WSAEADDRNOTAVAIL). "
            "Check that the PC Bluetooth radio is powered on and initialized."
        )
    if winerror == 10013:
        return (
            "Bluetooth RFCOMM access denied (winerror=10013, WSAEACCES). "
            "Check Windows Bluetooth privacy permissions, firewall, or corporate Group Policy (GPO) restrictions."
        )
    suffix = f": {detail}" if detail else ""
    if winerror is not None:
        return f"Bluetooth Winsock error (winerror={winerror}){suffix}"
    return f"Bluetooth socket error{suffix}"


def _describe_outer_error(exc: OSError) -> tuple[tuple[object, ...], str]:
    if isinstance(exc, _SdpRegistrationError):
        classified = classify_winsock_error(exc.wsa_error)
        return (
            ("sdp", exc.wsa_error),
            f"WSASetServiceW registration failed (WSAError={exc.wsa_error}); "
            f"phone cannot discover BRIDGE_UUID ({BRIDGE_UUID}). {classified}",
        )
    winerror = getattr(exc, "winerror", None)
    key = ("os", winerror if winerror is not None else str(exc))
    return key, classify_winsock_error(winerror, exc)


def _is_peer_disconnect_error(exc: OSError) -> bool:
    if isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
        return True
    return getattr(exc, "winerror", None) in _PEER_DISCONNECT_WINERRORS


def _close_client(client_sock, rx_buffer: bytearray) -> None:
    rx_buffer.clear()
    try:
        client_sock.close()
    except OSError:
        pass


def _reject_oversized_line(client_sock, rx_buffer: bytearray) -> None:
    print("[warn] Line exceeded 64KB limit; closing client", flush=True)
    try:
        client_sock.sendall(b'{"ok":false,"err":"line_too_long"}\n')
    finally:
        _close_client(client_sock, rx_buffer)


def _handle_client_data(client_sock, rx_buffer: bytearray, process_fn=process_line) -> bool:
    """Read and process client frames; return False when client_sock is closed.

    Peer disconnect errors close only client_sock and return False. Local adapter
    errors (e.g. WSAENETDOWN 10050) close client_sock and re-raise to outer loop.
    """
    try:
        chunk = client_sock.recv(4096)
        if not chunk:
            print("[disconnected]", flush=True)
            _close_client(client_sock, rx_buffer)
            return False

        rx_buffer.extend(chunk)
        if len(rx_buffer) > MAX_LINE_BYTES and b"\n" not in rx_buffer:
            _reject_oversized_line(client_sock, rx_buffer)
            return False

        while True:
            newline_idx = rx_buffer.find(b"\n")
            if newline_idx == -1:
                break
            raw_line = bytes(rx_buffer[:newline_idx])
            del rx_buffer[: newline_idx + 1]
            if len(raw_line) > MAX_LINE_BYTES:
                _reject_oversized_line(client_sock, rx_buffer)
                return False
            try:
                text_line = raw_line.decode("utf-8")
            except UnicodeDecodeError:
                client_sock.sendall(b'{"ok":false,"err":"utf8"}\n')
                continue
            resp = process_fn(text_line)
            if resp is not None:
                client_sock.sendall((json.dumps(resp) + "\n").encode("utf-8"))

        if len(rx_buffer) > MAX_LINE_BYTES:
            _reject_oversized_line(client_sock, rx_buffer)
            return False

        return True
    except OSError as exc:
        _close_client(client_sock, rx_buffer)
        if _is_peer_disconnect_error(exc):
            print(f"[disconnected] ({exc})", flush=True)
            return False
        raise


def serve_forever(
    stop_event: threading.Event | None = None,
    cleanup_done: threading.Event | None = None,
) -> None:
    _configure_console_encoding()
    ok, env_error = check_environment()
    if not ok:
        print(f"[error] {env_error}", file=sys.stderr, flush=True)
        sys.exit(1)

    if stop_event is None:
        stop_event = _stop_event
        stop_event.clear()
    if cleanup_done is None:
        cleanup_done = _cleanup_done
    cleanup_done.clear()

    handler_registered = False
    if _kernel32 is not None and _CONSOLE_CTRL_HANDLER is not None:
        handler_registered = bool(_kernel32.SetConsoleCtrlHandler(_CONSOLE_CTRL_HANDLER, True))

    print("AKeyboard Windows 10/11 Bluetooth Bridge starting...", flush=True)
    print(
        "[tip] Switch Windows input method to English before dictating "
        "(Windows Zhuyin/Bopomofo IME may garble punctuation). "
        "使用前請先將 Windows 輸入法切換為英文（使用 Windows 注音輸入法會導致標點符號跑掉）。",
        flush=True,
    )
    backoff_index = 0
    last_error_key: tuple[object, ...] | None = None

    try:
        while not stop_event.is_set():
            server_sock: socket.socket | None = None
            client_sock: socket.socket | None = None
            sdp_registered = False
            port = 0
            retry_delay: int | None = None
            should_exit = False
            try:
                server_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                server_sock.bind(("00:00:00:00:00:00", BT_PORT_ANY))
                server_sock.listen(1)
                port = int(server_sock.getsockname()[1])
                if port <= 0:
                    raise OSError(f"Bluetooth RFCOMM bind returned invalid channel {port}")

                sdp_registered, err = _set_sdp_service(port, RNRSERVICE_REGISTER)
                if not sdp_registered:
                    raise _SdpRegistrationError(err, port)

                print(f"[ready] Waiting for phone Bluetooth connection (RFCOMM channel {port})...", flush=True)
                backoff_index = 0
                last_error_key = None

                rx_buffer = bytearray()
                while not stop_event.is_set():
                    watch = [server_sock] + ([client_sock] if client_sock is not None else [])
                    readable, _, _ = select.select(watch, [], [], SELECT_TIMEOUT_SECONDS)
                    if stop_event.is_set():
                        break

                    accepted_new = False
                    if server_sock in readable:
                        new_sock, addr = server_sock.accept()
                        if client_sock is not None:
                            _close_client(client_sock, rx_buffer)
                        else:
                            rx_buffer.clear()
                        client_sock = new_sock
                        accepted_new = True
                        print(f"[connected] {addr}", flush=True)

                    if not accepted_new and client_sock is not None and client_sock in readable:
                        if not _handle_client_data(client_sock, rx_buffer, process_line):
                            client_sock = None

            except KeyboardInterrupt:
                print("\nShutting down...", flush=True)
                should_exit = True
            except OSError as exc:
                if stop_event.is_set():
                    should_exit = True
                else:
                    retry_delay = BACKOFF_DELAYS_SECONDS[backoff_index]
                    backoff_index = min(backoff_index + 1, len(BACKOFF_DELAYS_SECONDS) - 1)
                    error_key, error_msg = _describe_outer_error(exc)
                    if error_key != last_error_key:
                        print(f"[warn] {error_msg} Retrying in {retry_delay}s (backoff up to 10s)...", flush=True)
                        last_error_key = error_key
            finally:
                if client_sock is not None:
                    try:
                        client_sock.close()
                    except OSError:
                        pass
                    client_sock = None
                if sdp_registered and port > 0:
                    _set_sdp_service(port, RNRSERVICE_DELETE)
                    sdp_registered = False
                if server_sock is not None:
                    try:
                        server_sock.close()
                    except OSError:
                        pass
                    server_sock = None

            if should_exit or stop_event.is_set():
                break
            if retry_delay is not None:
                try:
                    if stop_event.wait(retry_delay):
                        break
                except KeyboardInterrupt:
                    print("\nShutting down...", flush=True)
                    break
    finally:
        cleanup_done.set()
        if handler_registered and _kernel32 is not None and _CONSOLE_CTRL_HANDLER is not None:
            _kernel32.SetConsoleCtrlHandler(_CONSOLE_CTRL_HANDLER, False)


def main() -> int:
    _configure_console_encoding()
    ok, env_error = check_environment()
    if not ok:
        print(f"[error] {env_error}", file=sys.stderr, flush=True)
        return 1
    serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
