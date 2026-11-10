#!/usr/bin/env python3
"""Swap selected classes*.dex entries from a patched APK into the original APK.

Why: apktool rebuilds dex correctly but drops the base APK's extra resource
packages (0x80-0x84). Keep the original APK's resources/manifest bytes and
replace only the dex files that contain the patched classes.

Usage:
  python3 swap_dex.py <original.apk> <patched.apk> <output.apk> [dex ...]

Default dex list: classes36.dex classes37.dex classes38.dex classes40.dex
"""

import sys
import zipfile

DEFAULT = ["classes36.dex", "classes37.dex", "classes38.dex", "classes40.dex"]


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    original, patched, out = sys.argv[1:4]
    names = sys.argv[4:] or DEFAULT

    with zipfile.ZipFile(original) as zo, zipfile.ZipFile(patched) as zp, \
            zipfile.ZipFile(out, "w") as zout:
        have = set(zp.namelist())
        missing = [n for n in names if n not in have]
        if missing:
            raise SystemExit("patched APK missing: %s" % ", ".join(missing))
        for item in zo.infolist():
            data = zp.read(item.filename) if item.filename in names \
                else zo.read(item.filename)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
    print("swapped %s -> %s" % (", ".join(names), out))


if __name__ == "__main__":
    main()
