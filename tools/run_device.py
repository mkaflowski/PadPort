"""Install the current build on a device through adb and start PadPort.

Usage:
  python tools/run_device.py                      # install dist APK, start the library
  python tools/run_device.py --build              # build first (tests + lint + APK), then install
  python tools/run_device.py --play "To the Moon" # also press Play on that game's card
  python tools/run_device.py -s 5b47bb6           # choose the device (default: the only physical one)
  python tools/run_device.py --no-install         # only (re)start the app
  python tools/run_device.py --log                # afterwards follow the RGSS/PadPort log

Building an APK happens only with --build (see AGENTS.md).
"""
from __future__ import annotations
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / ".tools/android-sdk/platform-tools/adb.exe"
APK = ROOT / "dist/PadPort-0.6-debug.apk"
PACKAGE = "pl.padport.app"


def adb(serial: str, *args: str, check: bool = True, timeout: int = 300) -> str:
    result = subprocess.run([str(ADB), "-s", serial, *args], capture_output=True, timeout=timeout)
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    if check and result.returncode:
        raise SystemExit(f"adb {' '.join(args)} failed:\n{output}")
    return output


def pick_device(requested: str | None) -> str:
    lines = subprocess.run([str(ADB), "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    devices = [line.split()[0] for line in lines if line.strip().endswith("device")]
    if requested:
        if requested not in devices:
            raise SystemExit(f"Device {requested} is not connected. Connected: {devices}")
        return requested
    physical = [d for d in devices if not d.startswith("emulator-")]
    if len(physical) == 1:
        return physical[0]
    raise SystemExit(f"Choose a device with -s. Connected: {devices}")


def build() -> None:
    subprocess.run([sys.executable, str(ROOT / "tools/build.py"), "testDebugUnitTest", "lintDebug", "assembleDebug"],
                   cwd=ROOT, check=True)


def press_play(serial: str, title: str) -> None:
    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    nodes = []
    for _ in range(6):
        if adb(serial, "shell", "pidof", PACKAGE + ":rgss", check=False).strip():
            print("An RGSS game is already running (RgssActivity).")
            return
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/padport-window.xml")
        xml = subprocess.run([str(ADB), "-s", serial, "exec-out", "cat", "/sdcard/padport-window.xml"], capture_output=True).stdout
        nodes = list(ET.fromstring(xml).iter("node"))
        titles = [n for n in nodes if n.get("text") == title]
        if titles:
            top = bounds(titles[0])[1]
            buttons = sorted((n for n in nodes if n.get("text") in ("Play", "Graj") and bounds(n)[1] > top),
                             key=lambda n: bounds(n)[1])
            if buttons:
                b = bounds(buttons[0])
                adb(serial, "shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
                print(f"Started: {title}")
                return
        size = re.findall(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size"))[-1]
        w, h = int(size[0]), int(size[1])
        adb(serial, "shell", "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "300")
        time.sleep(.8)
    visible = [n.get("text") for n in nodes if n.get("text")]
    raise SystemExit(f"Card '{title}' not found. Visible texts: {visible}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-s", "--serial")
    parser.add_argument("--build", action="store_true", help="run tools/build.py (tests, lint, APK) first")
    parser.add_argument("--no-install", action="store_true")
    parser.add_argument("--play", metavar="TITLE", help="press Play on the card with this title")
    parser.add_argument("--log", action="store_true", help="follow the log after starting")
    args = parser.parse_args()

    serial = pick_device(args.serial)
    model = adb(serial, "shell", "getprop", "ro.product.model").strip()
    print(f"Device: {serial} ({model})")
    if args.build:
        build()
    if not args.no_install:
        if not APK.is_file():
            raise SystemExit(f"No APK at {APK}; use --build.")
        print(f"Installing {APK.name} ({APK.stat().st_size // 1024} KiB)…")
        print(adb(serial, "install", "-r", str(APK)).strip().splitlines()[-1])
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    adb(serial, "logcat", "-c", check=False)
    adb(serial, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "--activity-single-top", "-n", f"{PACKAGE}/.MainActivity")
    version = re.search(r"versionName=(\S+)", adb(serial, "shell", "dumpsys", "package", PACKAGE))
    print(f"PadPort {version.group(1) if version else '?'} started")
    if args.play:
        time.sleep(1.5)
        press_play(serial, args.play)
    if args.log:
        print("Log (Ctrl+C to stop):")
        subprocess.run([str(ADB), "-s", serial, "logcat", "-v", "time", "-s",
                        "mkxp:D", "PadPort-RGSS:*", "SDL:*", "AndroidRuntime:E", "chromium:I"])


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
