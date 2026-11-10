#!/usr/bin/env python3
"""Build the patcher launcher icon: realme Link's foreground on a red tile.

The realme artwork is NOT stored in this repository.  The script reads the
adaptive-icon foreground from either a realme Link APK or its decoded res/
tree and writes only generated files under --out (normally build/res/).

Usage:
  python3 make_icon.py --apk /path/to/base.apk  --out build/res
  python3 make_icon.py --res /path/to/res       --out build/res
"""

import argparse
import io
import os
import re
import zipfile

from PIL import Image

RED = (230, 0, 18, 255)  # #E60012
LEGACY = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def foregrounds_from_apk(path):
    found = {}
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            m = re.fullmatch(
                r"res/mipmap-([a-z]+)(?:-v\d+)?/ic_launcher_me_foreground\.png",
                name,
            )
            if m and m.group(1) in LEGACY:
                found[m.group(1)] = z.read(name)
    return found


def foregrounds_from_res(root):
    found = {}
    for density in LEGACY:
        path = os.path.join(root, f"mipmap-{density}", "ic_launcher_me_foreground.png")
        if os.path.isfile(path):
            with open(path, "rb") as f:
                found[density] = f.read()
    return found


def make(found, out):
    if not found:
        raise SystemExit("no ic_launcher_me_foreground.png found")

    os.makedirs(os.path.join(out, "mipmap-anydpi-v26"), exist_ok=True)
    with open(os.path.join(out, "mipmap-anydpi-v26", "ic_launcher.xml"), "w") as f:
        f.write(ADAPTIVE_XML)

    for density, size in LEGACY.items():
        if density not in found:
            continue
        fg = Image.open(io.BytesIO(found[density])).convert("RGBA")
        folder = os.path.join(out, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)

        fg.save(os.path.join(folder, "ic_launcher_foreground.png"))

        background = Image.new("RGBA", fg.size, RED)
        background.save(os.path.join(folder, "ic_launcher_background.png"))

        legacy = Image.new("RGBA", (size, size), RED)
        scaled = fg.resize((size, size), Image.LANCZOS)
        legacy.alpha_composite(scaled)
        legacy.save(os.path.join(folder, "ic_launcher.png"))


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--apk")
    group.add_argument("--res")
    parser.add_argument("--out", default="build/res")
    args = parser.parse_args()

    found = foregrounds_from_apk(args.apk) if args.apk else foregrounds_from_res(args.res)
    make(found, args.out)
    print(f"icon: {len(found)} densities -> {args.out}")


if __name__ == "__main__":
    main()
