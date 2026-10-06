"""Build and test the development APK, and prepare dist/PadPort-0.6.1-debug.apk."""
import os
import shutil
import subprocess
import sys
from bootstrap import prepare,ROOT

if __name__=="__main__":
    if hasattr(sys.stdout,"reconfigure"): sys.stdout.reconfigure(encoding="utf-8")
    gradle,env=prepare()
    from fetch_rgss_engine import fetch
    fetch()
    command=[str(gradle/("bin/gradle.bat" if os.name=="nt" else "bin/gradle")),"--no-daemon","--console=plain"]
    tasks=sys.argv[1:] or ["testDebugUnitTest","assembleDebug"]
    subprocess.run(command+tasks,cwd=ROOT,env=env,check=True)
    apk=ROOT/"app/build/outputs/apk/debug/app-debug.apk"
    if "assembleDebug" in tasks:
        from package_release import package
        package()
