"""Learn a keyboard-mode HID button through the real UI, then verify Gamepad API."""
import json
import re
import sys
import time
from android_smoke import shell,ui,tap_text,snapshot,Uinput,OUT,adb,select_language

def click_node(node):
    b=list(map(int,re.findall(r"\d+",node.get("bounds",""))))
    shell("input","tap",str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.7)

def main():
    keyboard=Uinput(keyboard=True)
    try:
        shell("am","force-stop","pl.padport.app")
        shell("am","start","-W","-f","0x10008000","-n","pl.padport.app/.MainActivity")
        select_language("pl")
        tap_text("Kontrolery")
        spinner=next(n for n in ui().iter("node") if n.get("class")=="android.widget.Spinner")
        click_node(spinner);tap_text("PadPort Keyboard HID")
        shell("input","swipe","1100","680","1100","310","350")
        tap_text("0 · A / krzyżyk")
        keyboard.inject(1,44,1);keyboard.inject(1,44,0) # Linux KEY_Z -> Android KEYCODE_Z
        for _ in range(3):shell("input","swipe","1100","250","1100","690","150")
        tap_text("Tester —");time.sleep(1)
        keyboard.inject(1,44,1)
        pressed=snapshot();p=next(p for p in pressed["pads"] if p)
        assert p["buttons"][0]==1 and pressed["keys"]=="0",pressed
        keyboard.inject(1,44,0)
        released=snapshot();p=next(p for p in released["pads"] if p)
        assert p["buttons"][0]==0 and released["keys"]=="0",released
        (OUT/"android-keyboard-mapping.json").write_text(json.dumps({"pressed":pressed,"released":released},ensure_ascii=False,indent=2),encoding="utf-8")
        (OUT/"android-keyboard-mapping.png").write_bytes(adb("exec-out","screencap","-p").stdout)
        print("Keyboard-mode HID learned through UI: Z -> Gamepad button 0, keyboard events = 0",flush=True)
    finally:keyboard.close()

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8");main()
