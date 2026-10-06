"""Library artwork, real encrypted MZ title, image picker, cache and fallback."""
import hashlib
import json
import struct
import sys
import time
import zlib
import xml.etree.ElementTree as ET
from android_smoke import adb,shell,ui,tap_text,cdp,select_language,OUT
from prepare_emulator import ROOT,SERIAL

def cache(name):
    result=adb("exec-out","run-as","pl.padport.app","cat","files/artwork/"+name,check=False)
    return result.stdout if result.returncode==0 and not result.stdout.startswith(b"cat:") else None

def wait_cache(name,present=True):
    for _ in range(50):
        value=cache(name)
        if (value is not None)==present:return value
        time.sleep(.2)
    raise AssertionError("Unexpected artwork cache: "+name)

def art_menu():
    tap_text("Opcje gry: Look Outside");tap_text("Grafika karty")

def make_png(path):
    width,height=640,320
    def chunk(tag,data):return struct.pack(">I",len(data))+tag+data+struct.pack(">I",zlib.crc32(tag+data)&0xffffffff)
    row=b"\0"+b"\x20\x70\xdc"*320+b"\x80\x35\xb0"*320
    data=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(row*height))+chunk(b"IEND",b"")
    path.write_bytes(data)

def main():
    assert SERIAL.startswith("emulator-")
    adb("install","-r",str(ROOT/"dist/PadPort-0.6.1-debug.apk"))
    shell("am","force-stop","com.google.android.documentsui");shell("am","force-stop","pl.padport.app")
    shell("am","start","-W","-f","0x10008000","-n","pl.padport.app/.MainActivity")
    select_language("pl")
    prefs=ET.fromstring(adb("exec-out","run-as","pl.padport.app","cat","shared_prefs/library.xml").stdout)
    games=json.loads(next(n.text for n in prefs if n.get("name")=="games"))
    game=next(g for g in games if g["title"]=="Look Outside");id=game["id"]
    auto=wait_cache(id+".auto.jpg");assert auto.startswith(b"\xff\xd8") and len(auto)>1000
    time.sleep(1)
    (OUT/"android-0.4-library.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    report={"automatic_jpeg_bytes":len(auto),"automatic_sha256":hashlib.sha256(auto).hexdigest()}
    if "--preview" in sys.argv:
        image_path=OUT/"android-0.4-library.png"
        print(json.dumps({"screenshot":str(image_path),"texts":[n.get("text") for n in ui().iter("node") if n.get("text")],**report},ensure_ascii=False,indent=2))
        return
    adb("root");adb("wait-for-device")
    image=OUT/"padport-cover-test.png";make_png(image)
    adb("push",str(image),"/sdcard/Download/padport-cover-test.png")
    shell("am","broadcast","-a","android.intent.action.MEDIA_SCANNER_SCAN_FILE","-d","file:///sdcard/Download/padport-cover-test.png")
    art_menu();tap_text("Wybierz obraz z urządzenia")
    tree=ui()
    if not any("padport-cover-test.png" in n.get("text","") for n in tree.iter("node")):
        if any(n.get("content-desc")=="Show roots" for n in tree.iter("node")):tap_text("Show roots")
        tap_text("Download")
    tap_text("padport-cover-test.png")
    custom=wait_cache(id+".custom.jpg");assert custom.startswith(b"\xff\xd8") and custom!=auto
    time.sleep(1)
    (OUT/"android-0.4-custom-artwork.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    report["custom_picker"]=True
    tap_text("Opcje gry: Look Outside");tap_text("Odśwież indeks")
    time.sleep(3)
    assert cache(id+".custom.jpg")==custom
    report["custom_survives_refresh"]=True
    art_menu();tap_text("Przywróć grafikę")
    wait_cache(id+".custom.jpg",False)
    restored=wait_cache(id+".auto.jpg");assert restored==auto
    report["restore_automatic"]=True
    # Simulate a game without title art in the emulator copy, never in the PC game.
    original="/sdcard/Download/LookOutside/img/titles1"
    saved="/sdcard/Download/LookOutside/img/padport-title-test"
    shell("mv",original,saved)
    try:
        art_menu();tap_text("Przywróć grafikę")
        wait_cache(id+".none")
        assert any(n.get("text")=="Graj" for n in ui().iter("node"))
        (OUT/"android-0.4-no-artwork.png").write_bytes(adb("exec-out","screencap","-p").stdout)
        report["missing_artwork_keeps_game_card"]=True
    finally:
        shell("mv",saved,original)
    art_menu();tap_text("Przywróć grafikę");wait_cache(id+".auto.jpg")
    tap_text("Graj")
    ready=False
    for _ in range(60):
        time.sleep(.3)
        try:
            ready=cdp("!!(window.SceneManager&&SceneManager._scene&&SceneManager._scene._started&&SceneManager._scene.constructor.name==='Scene_Title')")
        except Exception:continue
        if ready:break
    assert ready,"The library play button did not open the game"
    report["play_button_opens_game"]=True
    (OUT/"android-artwork-smoke.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report,indent=2))

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8");main()
