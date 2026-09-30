"""Manual APK check: automatic Android locales, matching WebView, English fallback."""
import json
import time
import sys
import android_smoke
from android_smoke import adb,shell,ui,tap_text,cdp,select_language,OUT
from prepare_emulator import ROOT,SERIAL

def texts():return [n.get("text") for n in ui().iter("node") if n.get("text")]
def library():
    shell("am","force-stop","pl.padport.app")
    shell("am","start","-W","-f","0x10008000","-n","pl.padport.app/.MainActivity")
    time.sleep(.5)

def check_ui(language):
    words={
        "en":{"tag":"Play RPG Maker games on Android!","play":"Play","options":"Game options: Look Outside","art":"Card artwork","search":"Search SteamGridDB","controllers":"Controllers ·","controller_title":"Controller","tester":"Tester —","heading":"What does the game see?","close":"Close tester"},
        "pl":{"tag":"Graj w gry z RPG Magera na Androidzie!","play":"Graj","options":"Opcje gry: Look Outside","art":"Grafika karty","search":"Szukaj w SteamGridDB","controllers":"Kontrolery ·","controller_title":"Kontroler","tester":"Tester —","heading":"Co widzi gra?","close":"Zamknij tester"}
    }[language]
    current=texts();assert "PadPort" in current and words["tag"] in current,current
    assert not any(t in current for t in ("English ▾","Polski ▾","Language / Język")),current
    (OUT/f"android-0.5-{language}-library.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    artwork=[]
    if words["play"] in current:
        tap_text(words["options"]);tap_text(words["art"])
        artwork=texts();assert words["search"] in artwork,artwork
        shell("input","keyevent","KEYCODE_BACK")
    tap_text(words["controllers"])
    controller=texts();assert words["controller_title"] in controller,controller
    (OUT/f"android-0.5-{language}-controller.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    tap_text(words["tester"]);time.sleep(1)
    if any(n.get("text")=="Got it" for n in ui().iter("node")):tap_text("Got it")
    web=cdp("({language:document.documentElement.lang,heading:document.querySelector('h1').textContent,body:document.body.innerText,backupError:PadPortI18n.text('wrong_backup')})")
    assert web["language"]==language and web["heading"]==words["heading"],web
    shell("input","keyevent","KEYCODE_BACK")
    menu=texts();assert words["close"] in menu,menu
    (OUT/f"android-0.5-{language}-tester-menu.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    library()
    assert words["tag"] in texts(),"Android locale was not respected after restarting"
    return {"library":current,"artwork_menu":artwork,"controller":controller,"webview":web,"player_menu":menu,"survives_restart":True}

def main():
    if "--serial" in sys.argv:android_smoke.SERIAL=sys.argv[sys.argv.index("--serial")+1]
    assert android_smoke.SERIAL.startswith("emulator-")
    adb("install","-r",str(ROOT/"dist/PadPort-0.5-debug.apk"))
    shell("am","force-stop","pl.padport.app")
    # A preference left by 0.5 must not override the Android language anymore.
    shell("run-as","pl.padport.app","mkdir","-p","shared_prefs")
    adb("shell","run-as","pl.padport.app","tee","shared_prefs/ui.xml",input=b'<map><string name="language">en</string></map>')
    try:
        select_language("pl")
        library()
        report={"polish":check_ui("pl"),"legacy_preference_ignored":True}
        select_language("en");report["english"]=check_ui("en")
        select_language("fr-FR");report["unsupported_language_fallback"]=check_ui("en")
        report["no_in_app_language_selector"]=True
        (OUT/"android-language-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
        print("Automatic Android PL/EN locales, English fallback, native/WebView agreement and no selector: OK")
    finally:
        shell("cmd","locale","set-app-locales","pl.padport.app")

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8");main()
