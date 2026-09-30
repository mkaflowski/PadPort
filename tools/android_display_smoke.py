"""Verify real canvas fit/touch coordinates and the native auto-hiding menu."""
import json
import re
import sys
import time
from android_smoke import adb,shell,ui,tap_text,cdp,select_language,OUT

def menu_node(tree):
    return next((n for n in tree.iter("node") if n.get("content-desc")=="Menu aplikacji"),None)

def geometry():
    value=None
    for _ in range(30):
        value=cdp("(()=>{const g=window.Graphics;if(!g||!g._canvas||!g._width||!g._height)return null;const r=g._canvas.getBoundingClientRect();return {viewport:[innerWidth,innerHeight],game:[g._width,g._height],scale:g._realScale,rect:[r.x,r.y,r.width,r.height],center:[g.pageToCanvasX(r.left+r.width/2),g.pageToCanvasY(r.top+r.height/2)]};})()")
        if value:break
        time.sleep(.3)
    assert value,"Game canvas did not become ready"
    vw,vh=value["viewport"];gw,gh=value["game"];x,y,w,h=value["rect"]
    expected=min(vw/gw,vh/gh)
    assert abs(value["scale"]-expected)<1e-6,value
    assert abs(w-gw*expected)<1 and abs(h-gh*expected)<1,value
    assert abs(x-(vw-w)/2)<1 and abs(y-(vh-h)/2)<1,value
    assert abs(value["center"][0]-gw/2)<=1 and abs(value["center"][1]-gh/2)<=1,value
    return value

def main():
    shell("am","force-stop","pl.padport.app")
    shell("am","start","-W","-f","0x10008000","-n","pl.padport.app/.MainActivity")
    select_language("pl")
    tap_text("Graj")
    for _ in range(60):
        time.sleep(.3)
        try:
            if cdp("!!(window.Graphics&&Graphics._canvas&&window.SceneManager&&SceneManager._scene&&SceneManager._scene._started)"):break
        except Exception:pass
    report={"default_geometry":geometry()}
    time.sleep(6)
    assert menu_node(ui()) is None,"Menu did not hide after five seconds"
    (OUT/"android-0.2-fit-hidden.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    cdp("window.__displayTouches=0;window.addEventListener('pointerdown',()=>__displayTouches++,true)")
    shell("input","tap","640","450")
    node=menu_node(ui());assert node is not None,"Touch did not reveal menu"
    assert cdp("__displayTouches")>=1,"Reveal swallowed the game touch"
    (OUT/"android-0.2-menu-visible.png").write_bytes(adb("exec-out","screencap","-p").stdout)
    bounds=list(map(int,re.findall(r"\d+",node.get("bounds"))))
    bx,by=(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2
    time.sleep(6)
    assert menu_node(ui()) is None
    before=cdp("__displayTouches")
    shell("input","tap",str(bx),str(by)) # Former hidden-button position belongs to the game.
    tree=ui()
    assert menu_node(tree) is not None
    assert not any(n.get("text")=="Wróć do gry" for n in tree.iter("node")),"Hidden button captured reveal tap"
    assert cdp("__displayTouches")==before+1
    shell("input","tap",str(bx),str(by))
    assert any(n.get("text")=="Wróć do gry" for n in ui().iter("node")),"Visible menu button did not open menu"
    tap_text("Wróć do gry")
    # A later touch restarts the timeout: still visible after the original deadline.
    shell("input","tap","640","450");time.sleep(3.2)
    shell("input","tap","640","450");time.sleep(1.8)
    assert menu_node(ui()) is not None,"Touch did not restart timeout"
    time.sleep(5.3)
    assert menu_node(ui()) is None,"Menu did not hide again"
    report["menu"]={"hidden_after_idle":True,"touch_reveals":True,"touch_forwarded":True,
        "hidden_hitbox_inactive":True,"visible_button_opens_dialog":True,"timeout_restarts":True}
    try:
        cdp("window.__displayToken='resize-keeps-game'")
        shell("wm","size","1600x900");time.sleep(2)
        report["wide_geometry"]=geometry()
        assert cdp("window.__displayToken")=="resize-keeps-game","Resize restarted the game"
    finally:
        shell("wm","size","reset");time.sleep(1)
    cdp("window.__oldIntegerScale=ConfigManager.intScaling;ConfigManager.intScaling=true;Graphics._updateAllElements()")
    report["integer_option_geometry"]=geometry()
    cdp("ConfigManager.intScaling=window.__oldIntegerScale")
    (OUT/"android-display-smoke.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8");main()
