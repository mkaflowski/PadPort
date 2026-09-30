"""Optional local Android 15 test device. Never selects or modifies a physical device."""
import hashlib
import argparse
import re
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from bootstrap import ROOT,TOOLS,SDK,download,unpack

SERIAL="emulator-5580"
ADB=SDK/"platform-tools/adb.exe"
EMULATOR=Path.home()/"AppData/Local/Android/Sdk/emulator/emulator.exe"
IMAGE=TOOLS/"emulator-image"
AVD=TOOLS/"avd"

def prepare(name="PadPort",port=5580):
    if not re.fullmatch(r"[A-Za-z0-9_-]+",name):raise ValueError("Invalid AVD name")
    serial=f"emulator-{port}"
    if not (IMAGE/"x86_64/system.img").exists():
        manifest=TOOLS/"system-images.xml"
        download("https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml",manifest)
        root=ET.parse(manifest).getroot()
        for node in root.iter():node.tag=node.tag.split("}")[-1]
        pkg=next(p for p in root.findall("remotePackage") if p.attrib.get("path")=="system-images;android-35;google_apis;x86_64")
        archive=pkg.find("archives/archive/complete")
        url=archive.findtext("url");sha=archive.findtext("checksum").strip()
        dest=TOOLS/Path(url).name
        print("Android image download size:",round(int(archive.findtext("size"))/1024/1024),"MiB",flush=True)
        if not dest.exists() or hashlib.sha1(dest.read_bytes()).hexdigest()!=sha:
            download("https://dl.google.com/android/repository/sys-img/google_apis/"+url,dest)
        if hashlib.sha1(dest.read_bytes()).hexdigest()!=sha:raise ValueError("Android image checksum mismatch")
        unpack(dest,IMAGE)
    AVD.mkdir(exist_ok=True)
    device=AVD/(name+".avd");device.mkdir(exist_ok=True)
    (AVD/(name+".ini")).write_text(f"avd.ini.encoding=UTF-8\npath={device}\ntarget=android-35\n",encoding="utf-8")
    settings={"AvdId":name,"avd.ini.displayname":name+" test","abi.type":"x86_64","hw.cpu.arch":"x86_64",
        "hw.cpu.ncore":"4","hw.ramSize":"3072","hw.lcd.width":"1280","hw.lcd.height":"800","hw.lcd.density":"240",
        "hw.keyboard":"yes","hw.mainKeys":"no","hw.gpu.enabled":"yes","hw.gpu.mode":"swiftshader_indirect",
        "disk.dataPartition.size":"4G","image.sysdir.1":str(IMAGE/"x86_64"),"tag.id":"google_apis",
        "tag.display":"Google APIs","PlayStore.enabled":"false","showDeviceFrame":"no"}
    (device/"config.ini").write_text("".join(k+"="+v+"\n" for k,v in settings.items()),encoding="utf-8")
    env=dict(os.environ,ANDROID_AVD_HOME=str(AVD),ANDROID_HOME=str(SDK))
    result=subprocess.run([str(ADB),"-s",serial,"get-state"],capture_output=True,text=True)
    if result.returncode:
        logs=ROOT/"test-results";logs.mkdir(exist_ok=True)
        with (logs/(name+"-emulator.stdout.log")).open("wb") as out,(logs/(name+"-emulator.stderr.log")).open("wb") as err:
            subprocess.Popen([str(EMULATOR),"-avd",name,"-port",str(port),"-no-window","-no-audio","-no-boot-anim",
                "-no-snapshot","-gpu","swiftshader_indirect","-camera-front","none","-camera-back","none"],
                env=env,stdout=out,stderr=err,stdin=subprocess.DEVNULL,creationflags=subprocess.DETACHED_PROCESS)
    for attempt in range(150):
        result=subprocess.run([str(ADB),"-s",serial,"shell","getprop","sys.boot_completed"],capture_output=True,text=True,timeout=10)
        if result.stdout.strip()=="1":
            print("Android emulator ready:",serial,flush=True)
            return
        time.sleep(2)
    raise RuntimeError("Emulator boot timeout; see test-results/emulator.*.log")

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser=argparse.ArgumentParser();parser.add_argument("--name",default="PadPort");parser.add_argument("--port",type=int,default=5580)
    args=parser.parse_args();prepare(args.name,args.port)
