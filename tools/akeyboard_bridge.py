"""Standalone Windows 11 Bluetooth RFCOMM receiver for AKeyboard Voice Bridge.

Uses only Python 3.10+ standard library (socket, select, json, ctypes, time).
"""

from __future__ import annotations

import ctypes
from ctypes import wintypes
import json
import select
import socket
import sys
import time
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


if sys.platform == "win32":
    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
    _user32.SendInput.restype = wintypes.UINT

    _ws2_32 = ctypes.WinDLL("ws2_32", use_last_error=True)
    _ws2_32.WSASetServiceW.argtypes = [ctypes.POINTER(WSAQUERYSETW), ctypes.c_int, wintypes.DWORD]
    _ws2_32.WSASetServiceW.restype = ctypes.c_int
    _ws2_32.WSAGetLastError.argtypes = []
    _ws2_32.WSAGetLastError.restype = ctypes.c_int
else:
    _user32 = None
    _ws2_32 = None


def _default_send_input(inputs: list[INPUT]) -> int:
    if _user32 is None:
        raise RuntimeError("SendInput is only supported on Windows")
    if not inputs:
        return 0
    arr = (INPUT * len(inputs))(*inputs)
    return int(_user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT)))


def _make_key_input(vk: int = 0, scan: int = 0, flags: int = 0) -> INPUT:
    return INPUT(
        type=INPUT_KEYBOARD,
        union=_INPUT_UNION(ki=KEYBDINPUT(wVk=vk, wScan=scan, dwFlags=flags, time=0, dwExtraInfo=0)),
    )


def build_virtual_key_inputs(vk: int) -> list[INPUT]:
    return [
        _make_key_input(vk=vk, flags=0),
        _make_key_input(vk=vk, flags=KEYEVENTF_KEYUP),
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


def send_virtual_key(vk: int, send_input_fn=_default_send_input) -> bool:
    inputs = build_virtual_key_inputs(vk)
    return send_input_fn(inputs) == len(inputs)


def send_unicode_text(text: str, send_input_fn=_default_send_input, max_units: int = UNICODE_CHUNK_UNITS) -> bool:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    segments = normalized.split("\n")
    for seg_index, segment in enumerate(segments):
        if segment:
            for chunk in chunk_utf16_units(segment, max_units=max_units):
                inputs: list[INPUT] = []
                for unit in chunk:
                    inputs.append(_make_key_input(vk=0, scan=unit, flags=KEYEVENTF_UNICODE))
                    inputs.append(_make_key_input(vk=0, scan=unit, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
                if send_input_fn(inputs) != len(inputs):
                    return False
        if seg_index < len(segments) - 1:
            if not send_virtual_key(VK_RETURN, send_input_fn=send_input_fn):
                return False
    return True


def process_line(line: str, send_input_fn=_default_send_input) -> dict[str, object] | None:
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
        ok = send_unicode_text(value, send_input_fn=send_input_fn)
        return {"ok": True} if ok else {"ok": False, "err": "uipi"}

    if msg_type == "key":
        key = payload.get("key")
        vk_map = {"ENTER": VK_RETURN, "BACKSPACE": VK_BACK}
        vk = vk_map.get(key)
        if vk is None:
            return {"ok": False, "err": "bad_key"}
        ok = send_virtual_key(vk, send_input_fn=send_input_fn)
        return {"ok": True} if ok else {"ok": False, "err": "uipi"}

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


def serve_forever() -> None:
    print("AKeyboard Windows 11 Bluetooth Bridge starting...", flush=True)
    while True:
        server_sock: socket.socket | None = None
        client_sock: socket.socket | None = None
        sdp_registered = False
        port = 0
        try:
            server_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            server_sock.bind(("00:00:00:00:00:00", BT_PORT_ANY))
            server_sock.listen(1)
            port = int(server_sock.getsockname()[1])

            sdp_registered, err = _set_sdp_service(port, RNRSERVICE_REGISTER)
            if not sdp_registered:
                print(f"[warn] WSASetServiceW registration failed (WSAError={err}); listening on RFCOMM channel {port}", flush=True)
            else:
                print(f"[ready] Waiting for phone Bluetooth connection (RFCOMM channel {port})...", flush=True)

            rx_buffer = bytearray()
            while True:
                watch = [server_sock] + ([client_sock] if client_sock is not None else [])
                readable, _, _ = select.select(watch, [], [])

                if server_sock in readable:
                    new_sock, addr = server_sock.accept()
                    if client_sock is not None:
                        try:
                            client_sock.close()
                        except OSError:
                            pass
                    client_sock = new_sock
                    rx_buffer.clear()
                    print(f"[connected] {addr}", flush=True)

                if client_sock is not None and client_sock in readable:
                    chunk = client_sock.recv(4096)
                    if not chunk:
                        print("[disconnected]", flush=True)
                        client_sock.close()
                        client_sock = None
                        rx_buffer.clear()
                        continue

                    rx_buffer.extend(chunk)
                    if len(rx_buffer) > MAX_LINE_BYTES and b"\n" not in rx_buffer:
                        print("[warn] Line exceeded 64KB limit; closing client", flush=True)
                        client_sock.close()
                        client_sock = None
                        rx_buffer.clear()
                        continue

                    while b"\n" in rx_buffer:
                        raw_line, _, rest = rx_buffer.partition(b"\n")
                        rx_buffer = bytearray(rest)
                        if len(raw_line) > MAX_LINE_BYTES:
                            continue
                        try:
                            text_line = raw_line.decode("utf-8")
                        except UnicodeDecodeError:
                            client_sock.sendall(b'{"ok":false,"err":"utf8"}\n')
                            continue
                        resp = process_line(text_line)
                        if resp is not None:
                            client_sock.sendall((json.dumps(resp) + "\n").encode("utf-8"))

        except KeyboardInterrupt:
            print("\nShutting down...", flush=True)
            break
        except OSError as exc:
            print(f"[warn] Bluetooth socket error ({exc}); retrying in 2s...", flush=True)
            time.sleep(2)
        finally:
            if client_sock is not None:
                try:
                    client_sock.close()
                except OSError:
                    pass
            if sdp_registered and port > 0:
                _set_sdp_service(port, RNRSERVICE_DELETE)
            if server_sock is not None:
                try:
                    server_sock.close()
                except OSError:
                    pass


if __name__ == "__main__":
    serve_forever()
