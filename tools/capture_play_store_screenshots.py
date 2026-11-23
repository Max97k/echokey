# Copyright 2026 Max97k
# SPDX-License-Identifier: Apache-2.0

import os
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image

ADB = r"D:\Android\Sdk\platform-tools\adb.exe"
EMULATOR = r"D:\Android\Sdk\emulator\emulator.exe"
OUT_DIR = "play_store_assets/screenshots"
os.makedirs(OUT_DIR, exist_ok=True)

APP_ID = "com.max97k.akeyboard"
APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"

def adb(args, check=True):
    cmd = [ADB] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and res.returncode != 0:
        print("ADB Error:", res.stderr.strip())
    return res

def get_device():
    res = adb(["devices"])
    for line in res.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device" and parts[0].startswith("emulator-"):
            return parts[0]
    return None

def capture(local_path):
    remote = "/sdcard/sc.png"
    adb(["shell", "screencap", "-p", remote])
    adb(["pull", remote, local_path])
    adb(["shell", "rm", "-f", remote])
    im = Image.open(local_path)
    extrema = im.convert("L").getextrema()
    print(f"Captured: {local_path}, Size: {im.size}, Extrema: {extrema}, Bytes: {os.path.getsize(local_path)}")
    return extrema != (0, 0)

def find_node_bounds(predicate):
    adb(["shell", "uiautomator", "dump", "/sdcard/window_dump.xml"])
    adb(["pull", "/sdcard/window_dump.xml", "tools/window_dump.xml"])
    try:
        tree = ET.parse("tools/window_dump.xml")
        root = tree.getroot()
        for node in root.iter("node"):
            if predicate(node):
                bounds = node.attrib.get("bounds", "")
                if bounds:
                    import re
                    m = re.findall(r"\[(\d+),(\d+)\]", bounds)
                    if len(m) == 2:
                        x1, y1 = int(m[0][0]), int(m[0][1])
                        x2, y2 = int(m[1][0]), int(m[1][1])
                        return (x1 + x2) // 2, (y1 + y2) // 2
    except Exception as e:
        print("XML Parse error:", e)
    return None

def tap_node(predicate, default_x=540, default_y=1200):
    center = find_node_bounds(predicate)
    if center:
        print(f"Tapping node at {center}")
        adb(["shell", "input", "tap", str(center[0]), str(center[1])])
    else:
        print(f"Node fallback tap: {default_x}, {default_y}")
        adb(["shell", "input", "tap", str(default_x), str(default_y)])

def main():
    dev = get_device()
    proc = None
    if not dev:
        print("Starting emulator...")
        proc = subprocess.Popen([
            EMULATOR, "-avd", "akeyboard_api36",
            "-no-window", "-no-audio", "-no-snapshot-save",
            "-gpu", "swiftshader_indirect"
        ])
        deadline = time.time() + 120
        while time.time() < deadline:
            dev = get_device()
            if dev: break
            time.sleep(3)
        if not dev:
            raise RuntimeError("Emulator failed to start")
        print("Waiting for boot...")
        adb(["wait-for-device"])
        boot_deadline = time.time() + 180
        while time.time() < boot_deadline:
            if adb(["shell", "getprop", "sys.boot_completed"], check=False).stdout.strip() == "1":
                break
            time.sleep(2)

    print("Device ready:", dev)

    # Install & Setup
    print("Installing app...")
    adb(["install", "-r", "-d", APK_PATH])
    adb(["shell", "pm", "grant", APP_ID, "android.permission.RECORD_AUDIO"], check=False)
    adb(["shell", "pm", "grant", APP_ID, "android.permission.BLUETOOTH_CONNECT"], check=False)

    # 1. Screenshot 1: PC Bluetooth Voice Remote Screen
    print("Step 1: Open Settings -> Agree -> Open PC Remote")
    adb(["shell", "pm", "clear", APP_ID])
    adb(["shell", "am", "start", "-n", f"{APP_ID}/com.akeyboard.settings.SettingsActivity"])
    time.sleep(4)
    # Tap Agree
    tap_node(lambda n: "Agree" in n.attrib.get("text", "") or "agree" in n.attrib.get("resource-id", ""), default_x=540, default_y=2222)
    time.sleep(2)
    # Tap "Open PC Bluetooth Voice Remote"
    tap_node(lambda n: "Open PC Bluetooth" in n.attrib.get("text", "") or "btn_open_pc_remote" in n.attrib.get("resource-id", ""), default_x=540, default_y=327)
    time.sleep(3)
    p1 = os.path.join(OUT_DIR, "phone_01.png")
    capture(p1)

    # 2. Screenshot 2: System Keyboard Settings showing AKeyboard Voice enabled
    print("Step 2: System On-Screen Keyboard Settings")
    adb(["shell", "ime", "enable", f"{APP_ID}/com.akeyboard.ime.AKeyboardImeService"])
    adb(["shell", "ime", "set", f"{APP_ID}/com.akeyboard.ime.AKeyboardImeService"])
    adb(["shell", "am", "start", "-a", "android.settings.INPUT_METHOD_SETTINGS"])
    time.sleep(3)
    p2 = os.path.join(OUT_DIR, "phone_02.png")
    capture(p2)

    # 3. Screenshot 3: AKeyboard IME popup with Touchpad
    print("Step 3: AKeyboard IME popup")
    adb(["shell", "am", "start", "-a", "android.settings.SETTINGS"])
    time.sleep(3)
    # Tap Search bar in Settings
    tap_node(lambda n: "Search" in n.attrib.get("text", "") or "search" in n.attrib.get("resource-id", ""), default_x=540, default_y=178)
    time.sleep(3)
    p3 = os.path.join(OUT_DIR, "phone_03.png")
    capture(p3)

    # 4. Screenshot 4: AKeyboard IME active text & cursor
    print("Step 4: AKeyboard IME active text & cursor")
    adb(["shell", "input", "text", "AKeyboard Voice Dictation"])
    time.sleep(2)
    p4 = os.path.join(OUT_DIR, "phone_04.png")
    capture(p4)

    # Check images
    print("\n--- Summary of Screenshots ---")
    all_valid = True
    sizes = set()
    for fn in ["phone_01.png", "phone_02.png", "phone_03.png", "phone_04.png"]:
        path = os.path.join(OUT_DIR, fn)
        im = Image.open(path)
        sz = os.path.getsize(path)
        sizes.add(sz)
        extrema = im.convert("L").getextrema()
        is_ok = extrema != (0, 0) and extrema[0] != extrema[1]
        if not is_ok:
            all_valid = False
        print(f"{fn}: {im.size}, Valid Content: {is_ok}, FileSize: {sz} bytes")

    if proc:
        print("Stopping emulator...")
        adb(["emu", "kill"], check=False)
        proc.wait(timeout=10)

    print("Success (distinct & valid):", all_valid and len(sizes) == 4)

if __name__ == "__main__":
    main()
