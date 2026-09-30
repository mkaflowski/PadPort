"""Fetch the prebuilt RGSS engine (mkxp-z for Android) used for RPG Maker XP/VX/VX Ace.

The native libraries come from the public GitHub release of
https://github.com/BookerRues9/mkxp-z-android-reworked (tag v1.0.0, built by
GitHub Actions from commit 0828373e0ad65402eced54e6b45645fceacf6bea). The
archive is verified by SHA-256 and only lib/arm64-v8a/*.so is extracted into
app/src/main/jniLibs (not kept in version control). The matching SDL 2.26.3
Java glue lives in app/src/main/java/org/libsdl/app.

Licences: mkxp-z GPL-2.0-or-later, Ruby (Ruby/BSD-2-Clause), SDL2 & SDL_* (zlib),
OpenAL Soft (LGPL-2.1-or-later), OpenSSL 3 (Apache-2.0), libc++ (Apache-2.0 WITH
LLVM-exception). Distribute the resulting APK under GPL-3.0-or-later together
with the corresponding source (see app/src/main/assets/rgss/NOTICE.txt).
"""
from __future__ import annotations
import hashlib
import shutil
import sys
import zipfile
from pathlib import Path
from bootstrap import ROOT, TOOLS, download

URL = "https://github.com/BookerRues9/mkxp-z-android-reworked/releases/download/v1.0.0/unsigned-release.apk"
SHA256 = "e8bc82724dcb822c7f00e11df0b932c4a8d3a7b9167e28e129fc34f3d246b600"
ABI = "arm64-v8a"
LIBS = ("libSDL2.so", "libSDL2_image.so", "libSDL2_sound.so", "libSDL2_ttf.so",
        "libc++_shared.so", "libmkxp-z.so", "libopenal.so", "libruby.so")
ARCHIVE = TOOLS / "rgss" / "mkxpz-v1.0.0.apk"

# Length-preserving fixes of GLSL sources embedded in libmkxp-z.so (from
# mkxp-z shader/common.h and shader/tilemap.vert). On real GPUs (e.g. Adreno 740
# in the AYN Thor) "mediump" is 16-bit, so texture coordinates into large tile
# atlases lose precision and tiles vanish or flicker; SwiftShader in the
# emulator computes everything in 32 bits and hides the problem. The tilemap
# shader also indexed its 7-element autotile array out of bounds for ordinary
# tiles (undefined behaviour on real drivers).
PATCH_VERSION = "shaders-1"
PATCHES = (
    (b"precision mediump float;", b"precision highp   float;", 1),
    (b"    lowp int atIndex = int(tex.y / autotileH);",
     b"    int atIndex=int(min(tex.y/autotileH,6.0));".ljust(46), 1),
    (b"lowp int", b"     int", 5),
)


def patch(data: bytes) -> bytes:
    for old, new, count in PATCHES:
        assert len(old) == len(new), (old, new)
        found = data.count(old)
        if found != count:
            raise ValueError(f"libmkxp-z.so patch {old!r}: expected {count} occurrence(s), found {found}")
        data = data.replace(old, new)
    return data
TARGET = ROOT / "app/src/main/jniLibs" / ABI


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch() -> Path:
    stamp = TOOLS / "rgss" / "jnilibs.sha256"
    expected = SHA256 + "+" + PATCH_VERSION
    if stamp.is_file() and stamp.read_text().strip() == expected and all((TARGET / lib).is_file() for lib in LIBS):
        return TARGET
    if not ARCHIVE.is_file() or sha256(ARCHIVE) != SHA256:
        download(URL, ARCHIVE)
    if sha256(ARCHIVE) != SHA256:
        raise ValueError("RGSS engine checksum mismatch: " + str(ARCHIVE))
    if TARGET.exists():
        shutil.rmtree(TARGET)
    TARGET.mkdir(parents=True)
    with zipfile.ZipFile(ARCHIVE) as apk:
        for lib in LIBS:
            data = apk.read(f"lib/{ABI}/{lib}")
            if lib == "libmkxp-z.so":
                data = patch(data)
            (TARGET / lib).write_bytes(data)
    stamp.parent.mkdir(parents=True, exist_ok=True)
    stamp.write_text(expected + "\n", encoding="ascii")
    print("RGSS engine ready:", TARGET, flush=True)
    return TARGET


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    fetch()
