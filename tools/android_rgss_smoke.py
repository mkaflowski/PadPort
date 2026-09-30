"""RPG Maker XP (RGSS) test: original To the Moon folder -> SAF -> PadPort -> mkxp-z, on emulator-5580 only.

The game is never bundled; it is copied from the local Steam folder to the emulator's
Download directory and selected through the system folder picker like a user would.
"""
import json
import re
import sys
import time
from pathlib import Path
from android_smoke import adb, shell, ui, tap_text, Uinput, OUT, ROOT

GAME = Path(r"E:\SteamLibrary\steamapps\common\To the Moon\To the Moon")
DEST = "/sdcard/Download/ToTheMoon"
TITLE = "To the Moon"


def screenshot(name):
    (OUT / name).write_bytes(adb("exec-out", "screencap", "-p").stdout)


def logcat():
    return adb("logcat", "-d", "-v", "brief").stdout.decode("utf-8", errors="replace")


def play_button_for(title, scroll=True):
    for _ in range(4):
        found = find_play(title)
        if found or not scroll:
            return found
        shell("input", "swipe", "640", "700", "640", "200", "300")
        time.sleep(.6)
    return None


def find_play(title):
    tree = ui()
    nodes = list(tree.iter("node"))
    def box(n):
        return list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
    titles = [n for n in nodes if n.get("text") == title]
    if not titles:
        return None
    top = box(titles[0])[1]
    buttons = [n for n in nodes if n.get("text") in ("Play", "Graj") and box(n)[1] > top]
    buttons.sort(key=lambda n: box(n)[1])
    if not buttons:
        return None
    b = box(buttons[0])
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def main():
    OUT.mkdir(exist_ok=True)
    adb("install", "-r", str(ROOT / "dist/PadPort-0.5-debug.apk"), timeout=300)
    if "--skip-push" not in sys.argv:
        shell("rm", "-rf", DEST)
        shell("mkdir", "-p", DEST)
        for item in sorted(GAME.iterdir()):
            print("Copy to emulator:", item.name, flush=True)
            adb("push", str(item), DEST + "/", timeout=300)
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("am", "force-stop", "com.google.android.documentsui")
    shell("am", "force-stop", "pl.padport.app")
    shell("am", "start", "-W", "-n", "pl.padport.app/.MainActivity")
    time.sleep(1.5)
    if play_button_for(TITLE) is None:
        tap_text("Add game folder")
        tree = ui()
        if not any(n.get("text") == "ToTheMoon" for n in tree.iter("node")):
            if any(n.get("content-desc") == "Show roots" for n in tree.iter("node")):
                tap_text("Show roots")
            tap_text("Download")
        tap_text("ToTheMoon")
        tap_text("Use this folder")
        tap_text("Allow")
        for _ in range(60):
            time.sleep(1)
            if play_button_for(TITLE):
                break
    screenshot("android-rgss-library.png")
    target = play_button_for(TITLE)
    assert target, "To the Moon card not found"
    adb("logcat", "-c")
    started = time.time()
    shell("input", "tap", str(target[0]), str(target[1]))
    ready = None
    for _ in range(240):
        time.sleep(1)
        log = logcat()
        if "[PadPort] Ruby 1.8 compatibility" in log:
            ready = time.time() - started
            break
        if "FATAL EXCEPTION" in log or "Unable to prepare RGSS game" in log:
            raise RuntimeError(log[-4000:])
    assert ready is not None, logcat()[-4000:]
    pid = shell("pidof", "pl.padport.app:rgss")
    assert pid, "RGSS process is not running"
    time.sleep(20)  # splash screen fade-in
    screenshot("android-rgss-splash.png")
    pad = Uinput("usb")
    try:
        # Physical controller path: A -> PadPort RGSS mapping -> Enter -> Input::C.
        pad.inject(1, 304, 1); time.sleep(.3); pad.inject(1, 304, 0)
        time.sleep(8)
        screenshot("android-rgss-title.png")
        pad.inject(1, 304, 1); time.sleep(.3); pad.inject(1, 304, 0)
        time.sleep(20)
        screenshot("android-rgss-new-game.png")
    finally:
        pad.close()
    log = logcat()
    mkxp = [line for line in log.splitlines() if " mkxp" in line or "PadPort" in line]
    errors = [line for line in mkxp if "Error" in line and "Midi" not in line]
    report = {
        "device": "emulator-5580", "game": str(GAME), "uses_saf": True, "rgss_pid": pid,
        "seconds_until_scripts": round(ready, 1), "mkxp_log": mkxp[-40:], "errors": errors,
        "still_running": bool(shell("pidof", "pl.padport.app:rgss")),
    }
    (OUT / "android-rgss-smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert report["still_running"] and not errors, report


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
