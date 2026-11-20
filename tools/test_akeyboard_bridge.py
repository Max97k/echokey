import ctypes
import inspect
import io
import socket
import sys
import threading
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import akeyboard_bridge as bridge


class FakeClientSocket:
    def __init__(self, recv_items=None, send_exc: Exception | None = None):
        self._recv_items = list(recv_items or [])
        self._send_exc = send_exc
        self.sent = bytearray()
        self.closed = False
        self.events: list[tuple[str, object]] = []

    def recv(self, size: int) -> bytes:
        if not self._recv_items:
            return b""
        item = self._recv_items.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    def sendall(self, data: bytes) -> None:
        if self._send_exc is not None:
            raise self._send_exc
        self.sent.extend(data)
        self.events.append(("sendall", bytes(data)))

    def close(self) -> None:
        self.closed = True
        self.events.append(("close", None))


def _make_win_oserror(winerror: int, msg: str = "winsock error") -> OSError:
    exc = OSError(msg)
    exc.winerror = winerror
    return exc


class AKeyboardBridgeTests(unittest.TestCase):
    def tearDown(self):
        bridge._stop_event.clear()
        bridge._cleanup_done.clear()

    def test_win64_struct_sizes(self):
        self.assertEqual(ctypes.sizeof(bridge.SOCKADDR_BTH), 30)
        if ctypes.sizeof(ctypes.c_void_p) == 8:
            self.assertEqual(ctypes.sizeof(bridge.INPUT), 40)

    def test_chunk_utf16_preserves_surrogate_pairs(self):
        # 31 BMP chars + 1 emoji (2 UTF-16 code units = 33 total) must split at 31, not 32.
        text = ("a" * 31) + "\U0001F600"
        chunks = bridge.chunk_utf16_units(text, max_units=32)
        self.assertEqual(len(chunks), 2)
        self.assertEqual(len(chunks[0]), 31)
        self.assertEqual(len(chunks[1]), 2)

    def test_process_line_unicode_strips_newlines_without_vk_return(self):
        captured: list[list[bridge.INPUT]] = []

        def fake_send(inputs):
            captured.append(inputs)
            return len(inputs)

        resp = bridge.process_line(
            '{"type":"text","value":"繁體中文\\nOK"}',
            send_input_fn=fake_send,
            has_focus_fn=lambda: True,
            map_vk_fn=lambda vk: 0x1C,
        )
        self.assertEqual(resp, {"ok": True})
        # "繁體中文 OK" -> 7 BMP UTF-16 code units * 2 (down + up) = 14 events in 1 batch, no VK_RETURN.
        self.assertEqual([len(b) for b in captured], [14])
        all_events = [ev for batch in captured for ev in batch]
        self.assertTrue(all(ev.union.ki.wVk == 0 for ev in all_events))
        self.assertTrue(all((ev.union.ki.dwFlags & bridge.KEYEVENTF_UNICODE) != 0 for ev in all_events))
        decoded = "".join(chr(ev.union.ki.wScan) for ev in all_events[::2])
        self.assertEqual(decoded, "繁體中文 OK")

    def test_sanitize_unicode_text_and_newline_only_payload(self):
        self.assertEqual(bridge.sanitize_unicode_text("\r\n你好\r\n"), "你好")
        self.assertEqual(bridge.sanitize_unicode_text(" \n  \t "), "")
        self.assertEqual(bridge.sanitize_unicode_text("A\u2028B\u2029C\x85D"), "A B C D")
        self.assertEqual(bridge.sanitize_unicode_text("hello "), "hello ")

        captured: list[list[bridge.INPUT]] = []
        resp = bridge.process_line(
            '{"type":"text","value":"\\r\\n\\n"}',
            send_input_fn=lambda inputs: captured.append(inputs) or len(inputs),
            has_focus_fn=lambda: True,
        )
        self.assertEqual(resp, {"ok": True})
        self.assertEqual(captured, [])

    def test_process_line_keys_and_uipi_failure(self):
        resp = bridge.process_line(
            '{"type":"key","key":"BACKSPACE"}',
            send_input_fn=lambda inputs: 0,
            has_focus_fn=lambda: True,
            map_vk_fn=lambda vk: 0x1C,
        )
        self.assertEqual(resp, {"ok": False, "err": "uipi"})

        seen_vks: list[tuple[int, int]] = []

        def ok_send(inputs):
            seen_vks.append((inputs[0].union.ki.wVk, inputs[0].union.ki.wScan))
            return len(inputs)

        self.assertEqual(
            bridge.process_line(
                '{"type":"key","key":"ENTER"}',
                send_input_fn=ok_send,
                has_focus_fn=lambda: True,
                map_vk_fn=lambda vk: 0x1C,
            ),
            {"ok": True},
        )
        self.assertEqual(seen_vks, [(bridge.VK_RETURN, 0x1C)])

    def test_process_line_invalid_inputs(self):
        self.assertIsNone(bridge.process_line("   ", has_focus_fn=lambda: True))
        self.assertEqual(bridge.process_line("{bad json", has_focus_fn=lambda: True), {"ok": False, "err": "bad_json"})
        self.assertEqual(
            bridge.process_line('{"type":"key","key":"ESC"}', has_focus_fn=lambda: True),
            {"ok": False, "err": "bad_key"},
        )

    def test_build_virtual_key_inputs_populates_wscan_and_safe_none_fallback(self):
        inputs = bridge.build_virtual_key_inputs(bridge.VK_RETURN, map_vk_fn=lambda vk: 0x1C if vk == bridge.VK_RETURN else 0)
        self.assertEqual(len(inputs), 2)
        self.assertEqual(inputs[0].union.ki.wVk, bridge.VK_RETURN)
        self.assertEqual(inputs[0].union.ki.wScan, 0x1C)
        self.assertEqual(inputs[0].union.ki.dwFlags, 0)
        self.assertEqual(inputs[1].union.ki.wVk, bridge.VK_RETURN)
        self.assertEqual(inputs[1].union.ki.wScan, 0x1C)
        self.assertEqual(inputs[1].union.ki.dwFlags, bridge.KEYEVENTF_KEYUP)

        with mock.patch.object(bridge, "_user32", None):
            self.assertEqual(bridge._default_map_vk(bridge.VK_RETURN), 0)
            self.assertFalse(bridge._default_has_focus())
            fallback_inputs = bridge.build_virtual_key_inputs(bridge.VK_RETURN)
            self.assertEqual(fallback_inputs[0].union.ki.wScan, 0)

    def test_default_has_focus_checks_foreground_window_nonzero(self):
        fake_user32 = mock.Mock()
        fake_user32.GetForegroundWindow.return_value = None
        with mock.patch.object(bridge, "_user32", fake_user32):
            self.assertFalse(bridge._default_has_focus())

        fake_user32.GetForegroundWindow.return_value = 0
        with mock.patch.object(bridge, "_user32", fake_user32):
            self.assertFalse(bridge._default_has_focus())

        fake_user32.GetForegroundWindow.return_value = 0x123456
        with mock.patch.object(bridge, "_user32", fake_user32):
            self.assertTrue(bridge._default_has_focus())

    def test_process_line_no_focus(self):
        called = False

        def fail_if_called(_inputs):
            nonlocal called
            called = True
            return 0

        self.assertEqual(
            bridge.process_line(
                '{"type":"text","value":"hello"}',
                send_input_fn=fail_if_called,
                has_focus_fn=lambda: False,
            ),
            {"ok": False, "err": "no_focus"},
        )
        self.assertEqual(
            bridge.process_line(
                '{"type":"key","key":"ENTER"}',
                send_input_fn=fail_if_called,
                has_focus_fn=lambda: False,
                map_vk_fn=lambda vk: 0x1C,
            ),
            {"ok": False, "err": "no_focus"},
        )
        self.assertFalse(called)

    def test_partial_write_returns_send_failed(self):
        self.assertNotIn("get_last_error_fn", inspect.signature(bridge.send_virtual_key).parameters)
        self.assertNotIn("get_last_error_fn", inspect.signature(bridge.send_unicode_text).parameters)

        self.assertIsNone(
            bridge.send_virtual_key(
                bridge.VK_RETURN,
                send_input_fn=lambda inputs: len(inputs),
                map_vk_fn=lambda vk: 0x1C,
            )
        )
        self.assertEqual(
            bridge.send_virtual_key(
                bridge.VK_RETURN,
                send_input_fn=lambda inputs: 0,
                map_vk_fn=lambda vk: 0x1C,
            ),
            "uipi",
        )
        self.assertEqual(
            bridge.send_virtual_key(
                bridge.VK_RETURN,
                send_input_fn=lambda inputs: 1,
                map_vk_fn=lambda vk: 0x1C,
            ),
            "send_failed",
        )

        self.assertEqual(
            bridge.send_unicode_text("ab", send_input_fn=lambda inputs: 2),
            "send_failed",
        )
        self.assertEqual(
            bridge.process_line(
                '{"type":"text","value":"ab"}',
                send_input_fn=lambda inputs: 2,
                has_focus_fn=lambda: True,
            ),
            {"ok": False, "err": "send_failed"},
        )
        self.assertEqual(
            bridge.process_line(
                '{"type":"key","key":"BACKSPACE"}',
                send_input_fn=lambda inputs: 1,
                has_focus_fn=lambda: True,
                map_vk_fn=lambda vk: 0x0E,
            ),
            {"ok": False, "err": "send_failed"},
        )

    def test_invalid_json_and_validation_checked_before_focus(self):
        focus_calls = 0

        def tracking_focus() -> bool:
            nonlocal focus_calls
            focus_calls += 1
            return False

        cases = [
            ("   ", None),
            ("{not json", {"ok": False, "err": "bad_json"}),
            ("[1, 2]", {"ok": False, "err": "bad_payload"}),
            ('{"type":"mouse"}', {"ok": False, "err": "unknown_type"}),
            ('{"type":"text","value":123}', {"ok": False, "err": "bad_value"}),
            ('{"type":"key","key":"ESC"}', {"ok": False, "err": "bad_key"}),
            ('{"type":"key","key":["ESC"]}', {"ok": False, "err": "bad_key"}),
        ]
        for raw, expected in cases:
            self.assertEqual(bridge.process_line(raw, has_focus_fn=tracking_focus), expected)
        self.assertEqual(focus_calls, 0)

    def test_check_environment_diagnostics_and_startup_exit(self):
        ok, msg = bridge.check_environment(
            platform="win32",
            version_info=(3, 12, 10),
            has_af_bluetooth=True,
            has_btproto_rfcomm=True,
        )
        self.assertTrue(ok)
        self.assertEqual(msg, "")

        ok_os, msg_os = bridge.check_environment(
            platform="linux",
            version_info=(3, 12, 10),
            has_af_bluetooth=True,
            has_btproto_rfcomm=True,
        )
        self.assertFalse(ok_os)
        self.assertIn("win32", msg_os)

        ok_py, msg_py = bridge.check_environment(
            platform="win32",
            version_info=(3, 9, 13),
            has_af_bluetooth=True,
            has_btproto_rfcomm=True,
        )
        self.assertFalse(ok_py)
        self.assertIn("3.10", msg_py)

        ok_af, msg_af = bridge.check_environment(
            platform="win32",
            version_info=(3, 12, 10),
            has_af_bluetooth=False,
            has_btproto_rfcomm=True,
        )
        self.assertFalse(ok_af)
        self.assertIn("AF_BLUETOOTH", msg_af)

        ok_rfcomm, msg_rfcomm = bridge.check_environment(
            platform="win32",
            version_info=(3, 12, 10),
            has_af_bluetooth=True,
            has_btproto_rfcomm=False,
        )
        self.assertFalse(ok_rfcomm)
        self.assertIn("BTPROTO_RFCOMM", msg_rfcomm)

        reconfigured = []

        class FakeStream(io.StringIO):
            def reconfigure(self, **kwargs):
                reconfigured.append(kwargs)

        fake_out = FakeStream()
        fake_err = FakeStream()
        with (
            mock.patch.object(sys, "stdout", fake_out),
            mock.patch.object(sys, "stderr", fake_err),
            mock.patch.object(bridge, "check_environment", return_value=(False, "Missing socket.AF_BLUETOOTH")),
        ):
            code = bridge.main()
        self.assertEqual(code, 1)
        self.assertIn("Missing socket.AF_BLUETOOTH", fake_err.getvalue())
        self.assertEqual(
            reconfigured,
            [{"encoding": "utf-8", "errors": "replace"}, {"encoding": "utf-8", "errors": "replace"}],
        )

    def test_handle_client_data_line_too_long_without_newline(self):
        rx_buffer = bytearray()
        sock = FakeClientSocket([b"a" * (bridge.MAX_LINE_BYTES + 1)])
        called = False

        def process_fn(_line):
            nonlocal called
            called = True
            return {"ok": True}

        with redirect_stdout(io.StringIO()):
            keep_open = bridge._handle_client_data(sock, rx_buffer, process_fn)
        self.assertFalse(keep_open)
        self.assertFalse(called)
        self.assertTrue(sock.closed)
        self.assertEqual(len(rx_buffer), 0)
        self.assertEqual(
            sock.events,
            [("sendall", b'{"ok":false,"err":"line_too_long"}\n'), ("close", None)],
        )

    def test_handle_client_data_line_too_long_after_newline_partition(self):
        rx_buffer = bytearray(b"a" * bridge.MAX_LINE_BYTES)
        sock = FakeClientSocket([b"b\n"])
        called = False

        def process_fn(_line):
            nonlocal called
            called = True
            return {"ok": True}

        with redirect_stdout(io.StringIO()):
            keep_open = bridge._handle_client_data(sock, rx_buffer, process_fn)
        self.assertFalse(keep_open)
        self.assertFalse(called)
        self.assertTrue(sock.closed)
        self.assertEqual(len(rx_buffer), 0)
        self.assertEqual(
            sock.events,
            [("sendall", b'{"ok":false,"err":"line_too_long"}\n'), ("close", None)],
        )

    def test_handle_client_data_framing_and_utf8_error(self):
        rx_buffer = bytearray()
        seen = []

        def fake_process(line: str):
            seen.append(line)
            return {"ok": True}

        sock = FakeClientSocket([b'{"type":"text","value":"hi"}\n\xff\n{"partial":'])
        keep_open = bridge._handle_client_data(sock, rx_buffer, fake_process)
        self.assertTrue(keep_open)
        self.assertFalse(sock.closed)
        self.assertEqual(seen, ['{"type":"text","value":"hi"}'])
        self.assertEqual(bytes(rx_buffer), b'{"partial":')
        self.assertEqual(
            bytes(sock.sent),
            b'{"ok": true}\n{"ok":false,"err":"utf8"}\n',
        )

    def test_handle_client_data_peer_disconnect_vs_local_adapter_error(self):
        peer_errors = [
            ConnectionResetError("peer reset"),
            ConnectionAbortedError("peer aborted"),
            BrokenPipeError("broken pipe"),
            _make_win_oserror(10054, "WSAECONNRESET"),
            _make_win_oserror(10053, "WSAECONNABORTED"),
            _make_win_oserror(10058, "WSAESHUTDOWN"),
            _make_win_oserror(10060, "WSAETIMEDOUT"),
        ]
        for exc in peer_errors:
            rx_buffer = bytearray(b"pending")
            sock = FakeClientSocket([exc])
            with redirect_stdout(io.StringIO()):
                keep_open = bridge._handle_client_data(sock, rx_buffer, lambda _line: {"ok": True})
            self.assertFalse(keep_open, f"Expected False for peer disconnect {exc!r}")
            self.assertTrue(sock.closed)
            self.assertEqual(len(rx_buffer), 0)

        for local_code in (10050, 10047):
            rx_buffer = bytearray(b"pending")
            sock = FakeClientSocket([_make_win_oserror(local_code, "local error")])
            with self.assertRaises(OSError) as ctx:
                bridge._handle_client_data(sock, rx_buffer, lambda _line: {"ok": True})
            self.assertEqual(getattr(ctx.exception, "winerror", None), local_code)
            self.assertTrue(sock.closed)
            self.assertEqual(len(rx_buffer), 0)

    def test_winsock_error_classification_and_console_ctrl_handler(self):
        self.assertIn("10047", bridge.classify_winsock_error(10047))
        self.assertIn("10050", bridge.classify_winsock_error(10050))
        self.assertIn("10049", bridge.classify_winsock_error(10049))
        self.assertIn("10013", bridge.classify_winsock_error(10013))

        # CTRL_C_EVENT returns False so KeyboardInterrupt can run without setting stop_event.
        self.assertFalse(bridge._handle_console_ctrl(bridge.CTRL_C_EVENT))
        self.assertFalse(bridge._stop_event.is_set())

        # CTRL_CLOSE_EVENT / LOGOFF / SHUTDOWN set stop_event and wait on cleanup_done.
        for evt in (bridge.CTRL_CLOSE_EVENT, bridge.CTRL_LOGOFF_EVENT, bridge.CTRL_SHUTDOWN_EVENT):
            bridge._stop_event.clear()
            bridge._cleanup_done.set()
            with mock.patch.object(bridge._cleanup_done, "wait", wraps=bridge._cleanup_done.wait) as wait_spy:
                handled = bridge._handle_console_ctrl(evt)
            self.assertTrue(handled)
            self.assertTrue(bridge._stop_event.is_set())
            wait_spy.assert_called_once_with(3.5)

    def test_ctypes_signatures_declared_on_windows(self):
        if sys.platform != "win32":
            self.skipTest("Win32 ctypes assertions apply only on Windows")
        self.assertIsNotNone(bridge._CONSOLE_CTRL_HANDLER)
        self.assertEqual(len(bridge._user32.SendInput.argtypes), 3)
        self.assertEqual(bridge._user32.SendInput.restype, ctypes.wintypes.UINT)
        self.assertEqual(len(bridge._user32.MapVirtualKeyW.argtypes), 2)
        self.assertEqual(bridge._user32.MapVirtualKeyW.restype, ctypes.wintypes.UINT)
        self.assertEqual(bridge._user32.GetForegroundWindow.argtypes, [])
        self.assertEqual(bridge._user32.GetForegroundWindow.restype, ctypes.wintypes.HWND)
        self.assertEqual(len(bridge._ws2_32.WSASetServiceW.argtypes), 3)
        self.assertEqual(bridge._ws2_32.WSASetServiceW.restype, ctypes.c_int)
        self.assertEqual(bridge._ws2_32.WSAGetLastError.argtypes, [])
        self.assertEqual(bridge._ws2_32.WSAGetLastError.restype, ctypes.c_int)
        self.assertEqual(len(bridge._kernel32.SetConsoleCtrlHandler.argtypes), 2)
        self.assertEqual(bridge._kernel32.SetConsoleCtrlHandler.restype, ctypes.wintypes.BOOL)

    def test_serve_forever_peer_isolation_and_outer_backoff_lifecycle(self):
        stop_event = threading.Event()
        cleanup_done = threading.Event()
        sdp_calls = []
        wait_delays = []

        class FakeServerSocket:
            def __init__(self, idx: int, port: int = 5):
                self.idx = idx
                self.port = port
                self.bound_addr = None
                self.closed = False
                self.accept_queue = []

            def bind(self, addr):
                self.bound_addr = addr

            def listen(self, _backlog):
                pass

            def getsockname(self):
                return ("00:00:00:00:00:00", self.port)

            def accept(self):
                return self.accept_queue.pop(0), ("AA:BB:CC:DD:EE:FF", 1)

            def close(self):
                self.closed = True

        # Client 1: disconnects with ConnectionResetError -> must NOT close server 1.
        client1 = FakeClientSocket([ConnectionResetError("peer dropped")])
        # Client 2: raises WSAENETDOWN (10050) -> must propagate to outer loop and close server 1.
        client2 = FakeClientSocket([_make_win_oserror(10050, "adapter down")])

        srv1 = FakeServerSocket(1)
        srv1.accept_queue = [client1, client2]
        # Server 2, 3: WSASetServiceW fails with 10050 twice -> backoff 2 -> 5 -> 10, deduplicated logs.
        srv2 = FakeServerSocket(2)
        srv3 = FakeServerSocket(3)
        # Server 4: invalid port=0 rejected before SDP -> retries without RNRSERVICE_DELETE.
        srv_bad_port = FakeServerSocket(99, port=0)
        # Server 5: becomes ready (resets backoff & dedup), then local 10047 -> backoff resets to 2s.
        srv4 = FakeServerSocket(4)
        # Server 6: becomes ready, then select returns after stop_event is set -> clean exit.
        srv5 = FakeServerSocket(5)
        created_servers = [srv1, srv2, srv3, srv_bad_port, srv4, srv5]
        server_iter = iter(created_servers)

        def fake_socket(family, sock_type, proto):
            self.assertEqual(family, socket.AF_BLUETOOTH)
            self.assertEqual(sock_type, socket.SOCK_STREAM)
            self.assertEqual(proto, socket.BTPROTO_RFCOMM)
            return next(server_iter)

        sdp_outcomes = iter([(True, 0), (False, 10050), (False, 10050), (True, 0), (True, 0)])

        def fake_set_sdp(port, op):
            sdp_calls.append((port, op))
            if op == bridge.RNRSERVICE_REGISTER:
                return next(sdp_outcomes)
            return True, 0

        select_step = 0

        def fake_select(watch, _w, _e, timeout):
            nonlocal select_step
            self.assertEqual(timeout, 0.5)
            select_step += 1
            if select_step == 1:
                return ([srv1], [], [])  # accept client1
            if select_step == 2:
                return ([client1], [], [])  # client1 peer reset (isolated)
            if select_step == 3:
                self.assertFalse(srv1.closed)
                return ([srv1], [], [])  # accept client2
            if select_step == 4:
                return ([client2], [], [])  # client2 WSAENETDOWN -> outer loop (delay 2)
            if select_step == 5:
                # srv4 became ready; raise 10047 to verify backoff reset back to 2.
                raise _make_win_oserror(10047, "rfcomm unavailable")
            if select_step == 6:
                stop_event.set()
                return ([], [], [])
            return ([], [], [])

        def fake_wait(timeout=None):
            wait_delays.append(timeout)
            return False

        out = io.StringIO()
        with (
            mock.patch.object(bridge, "check_environment", return_value=(True, "")),
            mock.patch.object(bridge.socket, "socket", side_effect=fake_socket),
            mock.patch.object(bridge.select, "select", side_effect=fake_select),
            mock.patch.object(bridge, "_set_sdp_service", side_effect=fake_set_sdp),
            mock.patch.object(stop_event, "wait", side_effect=fake_wait),
            redirect_stdout(out),
        ):
            bridge.serve_forever(stop_event=stop_event, cleanup_done=cleanup_done)

        self.assertTrue(cleanup_done.is_set())
        self.assertEqual(srv1.bound_addr, ("00:00:00:00:00:00", bridge.BT_PORT_ANY))
        self.assertTrue(all(s.closed for s in created_servers))
        self.assertEqual(wait_delays, [2, 5, 10, 10, 2])
        self.assertEqual(
            sdp_calls,
            [
                (5, bridge.RNRSERVICE_REGISTER),
                (5, bridge.RNRSERVICE_DELETE),
                (5, bridge.RNRSERVICE_REGISTER),
                (5, bridge.RNRSERVICE_REGISTER),
                (5, bridge.RNRSERVICE_REGISTER),
                (5, bridge.RNRSERVICE_DELETE),
                (5, bridge.RNRSERVICE_REGISTER),
                (5, bridge.RNRSERVICE_DELETE),
            ],
        )
        logs = out.getvalue()
        # Consecutive identical WSASetServiceW 10050 error logs only once.
        self.assertIn("[tip]", logs)
        self.assertIn("English", logs)
        self.assertEqual(logs.count("WSASetServiceW registration failed"), 1)
        self.assertEqual(logs.count("[ready]"), 3)
        self.assertIn("invalid channel 0", logs)
        self.assertIn("10047", logs)


if __name__ == "__main__":
    unittest.main()
