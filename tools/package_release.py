"""Package the built APK, player instructions and checksums; never bundles a game."""
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def package():
    apk=ROOT/"app/build/outputs/apk/debug/app-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        assert archive.read("assets/bridge.js")== (ROOT/"app/src/main/assets/bridge.js").read_bytes(),"APK has an old bridge"
        assert archive.read("assets/ui-strings.js")== (ROOT/"app/src/main/assets/ui-strings.js").read_bytes(),"APK has old UI translations"
    dist=ROOT/"dist";dist.mkdir(exist_ok=True)
    target=dist/"PadPort-0.6.1-debug.apk";shutil.copy2(apk,target)
    shutil.copy2(ROOT/"JAK-URUCHOMIC.txt",dist/"JAK-URUCHOMIC.txt")
    shutil.copy2(ROOT/"HOW-TO-RUN.txt",dist/"HOW-TO-RUN.txt")
    digest=hashlib.sha256(target.read_bytes()).hexdigest()
    (dist/"SHA256SUMS.txt").write_text(digest+"  "+target.name+"\n",encoding="ascii")
    print(json.dumps({"apk":str(target),"bytes":target.stat().st_size,"sha256":digest},indent=2))
if __name__=="__main__":
    if hasattr(sys.stdout,"reconfigure"):sys.stdout.reconfigure(encoding="utf-8")
    package()
