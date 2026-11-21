import ctypes
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import akeyboard_bridge as bridge

MIN_PATH = Path(__file__).resolve().parent / "akeyboard_bridge.min.py"
spec = importlib.util.spec_from_file_location("akeyboard_bridge_min", MIN_PATH)
min_bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(min_bridge)


class MinBridgeParityTests(unittest.TestCase):
    def test_struct_sizes_parity(self):
        self.assertEqual(ctypes.sizeof(min_bridge.SB), ctypes.sizeof(bridge.SOCKADDR_BTH))
        self.assertEqual(ctypes.sizeof(min_bridge.QS), ctypes.sizeof(bridge.WSAQUERYSETW))
        self.assertEqual(ctypes.sizeof(min_bridge.CA), ctypes.sizeof(bridge.CSADDR_INFO))
        self.assertEqual(ctypes.sizeof(min_bridge.SA), ctypes.sizeof(bridge.SOCKET_ADDRESS))
        self.assertEqual(ctypes.sizeof(min_bridge.BL), ctypes.sizeof(bridge.BLOB))
        self.assertEqual(ctypes.sizeof(min_bridge.IN), ctypes.sizeof(bridge.INPUT))
        self.assertEqual(ctypes.sizeof(min_bridge.IN), 40)

    def test_guid_parity(self):
        # UUID "8e7b3f20-4c6a-4d8e-9a1b-2c3d4e5f6a7b"
        g = min_bridge.GD
        self.assertEqual(g.a, 0x8E7B3F20)
        self.assertEqual(g.b, 0x4C6A)
        self.assertEqual(g.c, 0x4D8E)
        self.assertEqual(list(g.d), [0x9A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F, 0x6A, 0x7B])

    def test_clean_sanitization_parity(self):
        samples = [
            "\r\n你好\r\n",
            " \n  \t ",
            "A\u2028B\u2029C\x85D",
            "hello ",
            "Multiple\r\nLines\nWith\tTabs",
            "",
            "   \r\n\t\x0b\x0c\r\n   ",
        ]
        for s in samples:
            self.assertEqual(min_bridge.cl(s), bridge.sanitize_unicode_text(s))

    def test_chunk_utf16_parity(self):
        text = ("a" * 31) + "\U0001F600"
        c_min = min_bridge.ck(text, 32)
        c_orig = bridge.chunk_utf16_units(text, 32)
        self.assertEqual(c_min, c_orig)
        self.assertEqual(len(c_min), 2)
        self.assertEqual(len(c_min[0]), 31)
        self.assertEqual(len(c_min[1]), 2)

    def test_process_line_parity(self):
        payloads = [
            '{"type":"text","value":"Hello World"}',
            '{"type":"key","key":"ENTER"}',
            '{"type":"key","key":"BACKSPACE"}',
            '{"type":"key","key":"UNKNOWN"}',
            '{"type":"unknown"}',
            "not json",
            "[]",
            '{"type":"text"}',
            '{"type":"key"}',
        ]
        for p in payloads:
            res_min = min_bridge.pr(
                p,
                sf=lambda n, arr, sz: n,
                ff=lambda: True,
                mf=lambda k, fl: 0x1C,
            )
            res_orig = bridge.process_line(
                p,
                send_input_fn=lambda inputs: len(inputs),
                has_focus_fn=lambda: True,
                map_vk_fn=lambda k: 0x1C,
            )
            self.assertEqual(res_min, res_orig)

        # Test focus = False
        for p in payloads[:3]:
            res_min = min_bridge.pr(
                p,
                sf=lambda n, arr, sz: n,
                ff=lambda: False,
                mf=lambda k, fl: 0x1C,
            )
            res_orig = bridge.process_line(
                p,
                send_input_fn=lambda inputs: len(inputs),
                has_focus_fn=lambda: False,
                map_vk_fn=lambda k: 0x1C,
            )
            self.assertEqual(res_min, res_orig)
            self.assertEqual(res_min, {"ok": False, "err": "no_focus"})

        # Test UIPI (send returns 0)
        for p in payloads[:3]:
            res_min = min_bridge.pr(
                p,
                sf=lambda n, arr, sz: 0,
                ff=lambda: True,
                mf=lambda k, fl: 0x1C,
            )
            res_orig = bridge.process_line(
                p,
                send_input_fn=lambda inputs: 0,
                has_focus_fn=lambda: True,
                map_vk_fn=lambda k: 0x1C,
            )
            self.assertEqual(res_min, res_orig)
            self.assertEqual(res_min, {"ok": False, "err": "uipi"})

    def test_client_data_handling(self):
        class FakeSock:
            def __init__(self, items):
                self.items = list(items)
                self.sent = bytearray()
                self.closed = False
            def recv(self, size):
                return self.items.pop(0) if self.items else b""
            def sendall(self, data):
                self.sent.extend(data)
            def close(self):
                self.closed = True

        # Normal message
        sock = FakeSock([b'{"type":"text","value":"ok"}\n'])
        buf = bytearray()
        ret = min_bridge.hc(sock, buf, pf=lambda line: {"ok": True})
        self.assertTrue(ret)
        self.assertEqual(sock.sent, b'{"ok": true}\n')

        # Invalid utf-8
        sock = FakeSock([b"\xff\xfe\n"])
        buf = bytearray()
        ret = min_bridge.hc(sock, buf, pf=lambda line: {"ok": True})
        self.assertTrue(ret)
        self.assertEqual(sock.sent, b'{"ok":false,"err":"utf8"}\n')

        # Line exceeds 64KB
        sock = FakeSock([b"a" * 65537])
        buf = bytearray()
        f = io.StringIO()
        with mock.patch("sys.stdout", f):
            ret = min_bridge.hc(sock, buf)
        self.assertFalse(ret)
        self.assertTrue(sock.closed)
        self.assertEqual(sock.sent, b'{"ok":false,"err":"line_too_long"}\n')
        self.assertIn("[WARN:E04]", f.getvalue())

        # Disconnect on empty recv
        sock = FakeSock([])
        buf = bytearray()
        f = io.StringIO()
        with mock.patch("sys.stdout", f):
            ret = min_bridge.hc(sock, buf)
        self.assertFalse(ret)
        self.assertTrue(sock.closed)
        self.assertIn("[DISC]", f.getvalue())


if __name__ == "__main__":
    unittest.main()
