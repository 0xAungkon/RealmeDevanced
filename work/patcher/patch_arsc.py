#!/usr/bin/env python3
"""Equal-length string replacement in resources.arsc.

Used for text that lives in resource string pools (app label, plurals). Same
constraint as AXML: the new string must encode to the same number of bytes in
the encoding actually used by the pool. This script replaces both UTF-8 and
UTF-16LE byte sequences found in resources.arsc.

Usage:
  python3 patch_arsc.py <input.apk> <mapping.json> <output.apk>

mapping.json:
  {"exact": {"realme Link": "realme Mods",
             "%s virtual devices": "                  ",
             "%s virtual device": "                 "}}

Warning: replacement is global within resources.arsc. "realme Link" also
occurs in UI copy/legal text; every occurrence is rewritten.
"""

import json
import sys
import zipfile


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    apk, mapfile, out = sys.argv[1:]
    with open(mapfile, encoding="utf-8") as fh:
        mapping = json.load(fh)["exact"]

    with zipfile.ZipFile(apk) as z:
        arsc = z.read("resources.arsc")

    for old, new in mapping.items():
        ob = old.encode("utf-8")
        nb = new.encode("utf-8")
        if len(ob) != len(nb):
            raise SystemExit("UTF-8 length mismatch: %r" % old)
        c8 = arsc.count(ob)
        arsc = arsc.replace(ob, nb)

        ob16 = old.encode("utf-16-le")
        nb16 = new.encode("utf-16-le")
        c16 = arsc.count(ob16)
        arsc = arsc.replace(ob16, nb16)
        print("%r: utf8=%d utf16=%d" % (old, c8, c16))

    with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(out, "w") as zout:
        for item in zin.infolist():
            data = arsc if item.filename == "resources.arsc" \
                else zin.read(item.filename)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
    print("wrote", out)


if __name__ == "__main__":
    main()
