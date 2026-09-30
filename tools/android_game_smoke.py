"""Read-only desktop game -> emulator SAF folder -> real Android WebView test."""
import json
import sys
import time
from pathlib import Path
from android_smoke import adb,shell,ui,tap_text,cdp,select_language,Uinput,OUT

GAME=Path(r"F:\DepotDownloaderMod\Look Outside")
DEST="/sdcard/Download/LookOutside"

def main():
    shell("mkdir","-p",DEST)
    if "--skip-push" not in sys.argv:
        for name in ("index.html","audio","css","data","effects","fonts","icon","img","js"):
            print("Copy to emulator:",name,flush=True)
            adb("push",str(GAME/name),DEST,timeout=180)
    shell("am","force-stop","com.google.android.documentsui")
    shell("am","force-stop","pl.padport.app")
    shell("am","start","-W","-f","0x10008000","-n","pl.padport.app/.MainActivity");time.sleep(1)
    select_language("pl")
    tree=ui()
    if not any(n.get("text")=="Graj" for n in tree.iter("node")):
        tap_text("Dodaj folder gry")
        tree=ui()
        print("Picker:",[(n.get("text"),n.get("content-desc")) for n in tree.iter("node") if n.get("text") or n.get("content-desc")],flush=True)
        if not any(n.get("text")=="LookOutside" for n in tree.iter("node")):
            if any(n.get("content-desc")=="Show roots" for n in tree.iter("node")):tap_text("Show roots")
            tap_text("Download")
        tap_text("LookOutside")
        tap_text("Use this folder")
        tap_text("Allow")
        for _ in range(30):
            time.sleep(1)
            if any(n.get("text")=="Graj" for n in ui().iter("node")):break
    tap_text("Graj");time.sleep(3)
    if any(n.get("text")=="Got it" for n in ui().iter("node")):tap_text("Got it")
    state={}
    for attempt in range(120):
        time.sleep(.5)
        try:
            state=cdp("({scene:window.SceneManager&&SceneManager._scene&&SceneManager._scene.constructor.name,started:window.SceneManager&&SceneManager._scene&&SceneManager._scene._started,error:document.getElementById('errorPrinter')?.textContent,focus:document.hasFocus(),secure:isSecureContext,audio:window.WebAudio&&WebAudio._context&&WebAudio._context.state})")
        except Exception:
            if attempt<10:continue
            raise
        if state.get("error"):raise RuntimeError(state)
        if state.get("started") and state.get("scene") in ("Scene_Title","Scene_Map"):break
    assert state.get("scene") in ("Scene_Title","Scene_Map"),state
    time.sleep(2) # Let the normal title-window fade/open animation finish.
    (OUT/"android-look-outside.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    print("Game started:",state,flush=True)
    audio_fallback=cdp("(async()=>{let url=AudioManager._bgmBuffer?._url;if(!url||!url.includes('.ogg'))return {tested:false};const encrypted=Utils.hasEncryptedAudio();if(encrypted)url+='_';const a=await fetch(url),b=await fetch(url.replace('.ogg','.m4a'));const x=new Uint8Array(await a.arrayBuffer()),y=new Uint8Array(await b.arrayBuffer());return {tested:true,encrypted,status:b.status,type:b.headers.get('Content-Type'),same:a.ok&&b.ok&&x.length===y.length&&x.every((v,i)=>v===y[i]),bytes:y.length};})()")
    if audio_fallback.get("tested"):
        expected_type="application/octet-stream" if audio_fallback["encrypted"] else "audio/ogg"
        assert audio_fallback["same"] and expected_type in audio_fallback["type"],audio_fallback
    pad=Uinput("usb")
    try:
        pad.inject(3,0,-32768)
        left=cdp("({left:Input.isPressed('left'),mapping:navigator.getGamepads().filter(Boolean)[0].mapping,nwjs:Utils.isNwjs(),native:JSON.parse(PadPortHost.snapshot())})")
        assert left["left"] and left["mapping"]=="standard" and not left["nwjs"],left
        pad.inject(3,0,0)
        # Start a new game through the physical-controller event path.
        pad.inject(1,304,1);pad.inject(1,304,0)
        for _ in range(50):
            time.sleep(.3)
            played=cdp("({scene:SceneManager._scene.constructor.name,map:window.$gameMap&&$gameMap.mapId(),error:document.getElementById('errorPrinter')?.textContent,audio:WebAudio._context?.state})")
            if played.get("error"):raise RuntimeError(played)
            if played.get("scene")=="Scene_Map" and played.get("map",0)>0:break
        assert played.get("map",0)>0,played
        time.sleep(6) # The intro deliberately starts on a black fade.
        played=cdp("({scene:SceneManager._scene.constructor.name,map:$gameMap.mapId(),error:document.getElementById('errorPrinter')?.textContent,audio:WebAudio._context?.state})")
        assert not played.get("error"),played
        (OUT/"android-look-outside-new-game.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    finally:pad.close()
    # Verify the actual MZ storage implementation in the game origin, not a mock.
    storage=cdp("StorageManager.saveObject('padport_smoke_test',{text:'żółw',value:42}).then(()=>StorageManager.loadObject('padport_smoke_test')).then(async value=>{const backup=await PadPort.collectSaves();await StorageManager.remove('padport_smoke_test');return {value,exported:Object.keys(backup.forage).length,format:backup.format};})")
    assert storage["value"]=={"text":"żółw","value":42} and storage["exported"]>0,storage
    report={"device":"emulator-5580","game":str(GAME),"uses_saf":True,"startup":state,"native_left":left,"new_game":played,"storage":storage,"audio_fallback":audio_fallback}
    (OUT/"android-game-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
