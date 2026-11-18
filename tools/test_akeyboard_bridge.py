import ctypes
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import akeyboard_bridge as bridge


class AKeyboardBridgeTests(unittest.TestCase):
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

    def test_process_line_unicode_and_newline(self):
        captured: list[list[bridge.INPUT]] = []

        def fake_send(inputs):
            captured.append(inputs)
            return len(inputs)

        resp = bridge.process_line('{"type":"text","value":"繁體中文\\nOK"}', send_input_fn=fake_send)
        self.assertEqual(resp, {"ok": True})
        # Batch 1: "繁體中文" (4 chars * 2 events = 8), Batch 2: VK_RETURN (2), Batch 3: "OK" (4)
        self.assertEqual([len(b) for b in captured], [8, 2, 4])
        self.assertEqual(captured[1][0].union.ki.wVk, bridge.VK_RETURN)
        self.assertEqual(captured[0][0].union.ki.dwFlags, bridge.KEYEVENTF_UNICODE)

    def test_process_line_keys_and_uipi_failure(self):
        resp = bridge.process_line('{"type":"key","key":"BACKSPACE"}', send_input_fn=lambda inputs: 0)
        self.assertEqual(resp, {"ok": False, "err": "uipi"})

        seen_vks: list[int] = []

        def ok_send(inputs):
            seen_vks.append(inputs[0].union.ki.wVk)
            return len(inputs)

        self.assertEqual(bridge.process_line('{"type":"key","key":"ENTER"}', send_input_fn=ok_send), {"ok": True})
        self.assertEqual(seen_vks, [bridge.VK_RETURN])

    def test_process_line_invalid_inputs(self):
        self.assertIsNone(bridge.process_line("   "))
        self.assertEqual(bridge.process_line("{bad json"), {"ok": False, "err": "bad_json"})
        self.assertEqual(bridge.process_line('{"type":"key","key":"ESC"}'), {"ok": False, "err": "bad_key"})


if __name__ == "__main__":
    unittest.main()
