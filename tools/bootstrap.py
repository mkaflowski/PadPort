"""Project-local Gradle/Android SDK bootstrap. Downloads verified official archives."""
from __future__ import annotations
import hashlib
import os
import shutil
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/".tools"
SDK=TOOLS/"android-sdk"
GRADLE="8.9"

def download(url,path):
    path.parent.mkdir(parents=True,exist_ok=True)
    print("Download",url,flush=True)
    for attempt in range(3):
        try:
            request=urllib.request.Request(url,headers={"User-Agent":"PadPort-build/0.1"})
            with urllib.request.urlopen(request,timeout=120) as response,path.with_suffix(path.suffix+".part").open("wb") as out:
                shutil.copyfileobj(response,out)
            path.with_suffix(path.suffix+".part").replace(path)
            return
        except Exception:
            if attempt==2: raise
            time.sleep(3)

def unpack(archive,dest):
    dest.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            p=dest/entry.filename
            if not p.resolve().is_relative_to(dest.resolve()): raise ValueError("Unsafe archive path")
        z.extractall(dest)

def prepare():
    TOOLS.mkdir(exist_ok=True)
    gradle=TOOLS/f"gradle-{GRADLE}"
    if not (gradle/"bin/gradle.bat").exists():
        archive=TOOLS/f"gradle-{GRADLE}-bin.zip"
        sha=TOOLS/(archive.name+".sha256")
        download(f"https://services.gradle.org/distributions/{archive.name}.sha256",sha)
        expected=sha.read_text().strip().split()[0]
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
            download(f"https://services.gradle.org/distributions/{archive.name}",archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected: raise ValueError("Gradle checksum mismatch")
        unpack(archive,TOOLS)
    if not (SDK/"platforms/android-35/android.jar").exists():
        manifest=TOOLS/"repository.xml"
        download("https://dl.google.com/android/repository/repository2-3.xml",manifest)
        root=ET.parse(manifest).getroot()
        for node in root.iter(): node.tag=node.tag.split("}")[-1]
        package=next(p for p in root.findall("remotePackage") if p.attrib.get("path")=="platforms;android-35")
        complete=package.find("archives/archive/complete")
        url=complete.findtext("url");check=complete.findtext("checksum").strip()
        archive=TOOLS/Path(url).name
        if not archive.exists() or hashlib.sha1(archive.read_bytes()).hexdigest()!=check:
            download("https://dl.google.com/android/repository/"+url,archive)
        if hashlib.sha1(archive.read_bytes()).hexdigest()!=check: raise ValueError("Android platform checksum mismatch")
        dest=TOOLS/"platform-unpack";unpack(archive,dest)
        source=next(p.parent for p in dest.rglob("android.jar"))
        (SDK/"platforms").mkdir(parents=True,exist_ok=True)
        shutil.move(str(source),str(SDK/"platforms/android-35"))
        shutil.rmtree(dest)
    existing=Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or
                  str(Path.home()/"AppData/Local/Android/Sdk"))
    if not (SDK/"build-tools/35.0.0").exists():
        source=existing/"build-tools/35.0.0"
        if not source.exists(): raise RuntimeError("Install Android build-tools 35.0.0 using Android Studio, then set ANDROID_HOME.")
        shutil.copytree(source,SDK/"build-tools/35.0.0")
    if (existing/"licenses").is_dir(): shutil.copytree(existing/"licenses",SDK/"licenses",dirs_exist_ok=True)
    (ROOT/"local.properties").write_text("sdk.dir="+SDK.as_posix()+"\n",encoding="utf-8")
    env=dict(os.environ)
    if os.name=="nt":
        candidates=[Path(env.get("JAVA_HOME","__missing__")),Path(r"C:\Program Files\Java\jdk-17.0.1"),Path(r"C:\Program Files\Android\Android Studio\jbr")]
        for candidate in candidates:
            if (candidate/"bin/javac.exe").exists(): env["JAVA_HOME"]=str(candidate);break
    env["ANDROID_HOME"]=str(SDK)
    env["GRADLE_USER_HOME"]=str(TOOLS/"gradle-cache")
    print("Gradle ready:",gradle,"SDK:",SDK,"JDK:",env.get("JAVA_HOME","PATH"),flush=True)
    return gradle,env

if __name__=="__main__":
    if hasattr(sys.stdout,"reconfigure"): sys.stdout.reconfigure(encoding="utf-8")
    prepare()
