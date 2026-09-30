"""Actual APK/WebView/native-HID test, exclusively on emulator-5580."""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from prepare_emulator import ADB,SERIAL,ROOT

OUT=ROOT/"test-results"
def adb(*args,check=True,**kw):
    result=subprocess.run([str(ADB),"-s",SERIAL,*args],capture_output=True,**kw)
    if check and result.returncode:raise RuntimeError(result.stderr.decode("utf-8",errors="replace")+result.stdout.decode("utf-8",errors="replace"))
    return result
def shell(*args):return adb("shell",*args).stdout.decode("utf-8",errors="replace").strip()
def ui():
    shell("uiautomator","dump","/sdcard/padport-window.xml")
    xml=adb("exec-out","cat","/sdcard/padport-window.xml").stdout
    (OUT/"android-window.xml").write_bytes(xml)
    return ET.fromstring(xml)
def tap_text(text):
    for attempt in range(6):
        tree=ui()
        nodes=list(tree.iter("node"))
        nodes.sort(key=lambda n:(text.casefold() not in (n.get("text","").casefold(),n.get("content-desc","").casefold()),n.get("class")!="android.widget.Button"))
        for node in nodes:
            if text.casefold() in node.get("text","").casefold() or text.casefold() in node.get("content-desc","").casefold():
                bounds=list(map(int,re.findall(r"\d+",node.get("bounds",""))))
                if len(bounds)==4:
                    shell("input","tap",str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2));time.sleep(.8);return
        time.sleep(.5)
    raise RuntimeError("Cannot find UI: "+text+"; visible: "+repr([(n.get('text'),n.get('content-desc')) for n in tree.iter('node') if n.get('text') or n.get('content-desc')]))
def cdp(expression):
    pid=shell("pidof","pl.padport.app").split()[0]
    adb("forward","tcp:9224","localabstract:webview_devtools_remote_"+pid)
    p=subprocess.run(["node",str(ROOT/"tools/cdp-eval.cjs")],input=expression,text=True,encoding="utf-8",capture_output=True,timeout=15)
    if p.returncode:raise RuntimeError(p.stderr)
    return json.loads(p.stdout)
def select_language(language):
    """Test-only Android locale change; there is no in-app language selector."""
    assert SERIAL.startswith("emulator-"),"Locale tests only run on dedicated emulators"
    locale={"en":"en-US","pl":"pl-PL"}.get(language,language)
    shell("cmd","locale","set-app-locales","pl.padport.app","--locales",locale)
    time.sleep(1)
def snapshot():
    return cdp("({pads:Array.from(navigator.getGamepads()).map(p=>p&&({id:p.id,axes:p.axes,buttons:p.buttons.map(b=>b.value)})),keys:document.getElementById('keys')?.textContent,native:JSON.parse(PadPortHost.snapshot())})")
class Uinput:
    def __init__(self,bus="usb",keyboard=False):
        self.log=(OUT/("uinput-"+bus+("-keyboard" if keyboard else "")+".log")).open("wb")
        self.process=subprocess.Popen([str(ADB),"-s",SERIAL,"shell","uinput","-"],stdin=subprocess.PIPE,stdout=self.log,stderr=self.log)
        keys=[44,45,46,57] if keyboard else [304,305,307,308,310,311,312,313,314,315,316,317,318]
        axes=[] if keyboard else [0,1,2,5,9,10,16,17]
        config=[{"type":100,"data":[1] if keyboard else [1,3]},{"type":101,"data":keys}]
        if axes:config.append({"type":103,"data":axes})
        absolute=[]
        for axis in axes:
            minimum,maximum,flat=(-32768,32767,1024) if axis<6 else (0,255,0) if axis<16 else (-1,1,0)
            absolute.append({"code":axis,"info":{"value":0,"minimum":minimum,"maximum":maximum,"fuzz":0,"flat":flat,"resolution":1}})
        self.send({"id":1,"command":"register","name":"PadPort "+("Keyboard HID" if keyboard else bus+" gamepad"),
            "vid":6353,"pid":keyboard and 2026 or 2025,"bus":bus,"configuration":config,"abs_info":absolute})
        time.sleep(2)
    def send(self,obj):self.process.stdin.write((json.dumps(obj)+"\n").encode());self.process.stdin.flush()
    def inject(self,*events):
        self.send({"id":1,"command":"inject","events":[*events,0,0,0]});time.sleep(.35)
    def close(self):
        self.process.stdin.close()
        try:self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:self.process.terminate()
        self.log.close();time.sleep(.6)

def main():
    assert SERIAL.startswith("emulator-")
    OUT.mkdir(exist_ok=True)
    adb("install","-r",str(ROOT/"dist/PadPort-0.5-debug.apk"))
    shell("input","keyevent","KEYCODE_WAKEUP")
    shell("wm","dismiss-keyguard")
    shell("am","force-stop","pl.padport.app")
    shell("am","start","-n","pl.padport.app/.MainActivity");time.sleep(1)
    select_language("pl");tap_text("Kontrolery");tap_text("Tester —")
    time.sleep(2)
    if any(n.get("text")=="Got it" for n in ui().iter("node")):tap_text("Got it")
    report={"device":SERIAL,"android":shell("getprop","ro.build.version.release"),"initial":snapshot(),"transports":{}}
    for bus in ("usb","bluetooth"):
        pad=Uinput(bus)
        try:
            initial=snapshot();assert any(p and "PadPort" in p["id"] for p in initial["pads"]),initial
            pad.inject(1,304,1,1,312,1,3,0,-32768,3,10,204)
            pressed=snapshot();p=next(p for p in pressed["pads"] if p)
            assert p["buttons"][0]==1,p
            assert p["axes"][0]<-.95,p
            assert .7<p["buttons"][6]<.9,p
            assert pressed["keys"]=="0",pressed
            pad.inject(1,304,0,1,312,0,3,0,0,3,10,0,3,17,-1)
            hat=snapshot();p=next(p for p in hat["pads"] if p)
            assert p["buttons"][0]==0 and p["buttons"][12]==1,p
            pad.inject(3,17,0)
            report["transports"][bus]={"pressed":pressed,"hat":hat}
        finally:pad.close()
        disconnected=snapshot();assert all(p is None for p in disconnected["pads"]),disconnected
    (OUT/"android-tester.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    report["disconnected"]=snapshot()
    (OUT/"android-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("Actual Android APK: USB/Bluetooth HID, axes, analog triggers, d-pad and disconnect OK; keyboard events = 0",flush=True)

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
