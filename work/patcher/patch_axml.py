#!/usr/bin/env python3
"""Equal-length string-pool patcher for Android binary AndroidManifest.xml.

Why equal length: AXML string-pool entries are referenced by offsets; changing
a string's encoded length would require rebuilding the pool and every offset.
Keeping the same byte length lets us overwrite in place.

Usage:
  python3 patch_axml.py <input.apk> <mapping.json> <output.apk>

mapping.json is either:
  {"exact": {"old string": "new string", ...}}
or a package-rename spec:
  {
    "old_package": "com.realme.link",
    "new_package": "com.realme.mods",
    "authorities": ["provider", "fileprovider", ...],
    "permission": "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "external": {"com.oplus.statistics.provider": "com.realme.mods.oplus.stats.s", ...}
  }

Only exact string-pool entries are rewritten, so Java class names such as
com.realme.link.home.MainActivity and action strings are left untouched.
"""

import json
import struct
import sys
import zipfile


def build_mapping(cfg):
    if "exact" in cfg:
        mapping = dict(cfg["exact"])
    else:
        old = cfg["old_package"]
        new = cfg["new_package"]
        mapping = {old: new}
        for suffix in cfg.get("authorities", []):
            mapping[old + "." + suffix] = new + "." + suffix
        perm = cfg.get("permission")
        if perm:
            mapping[old + "." + perm] = new + "." + perm
        mapping.update(cfg.get("external", {}))
    for o, n in mapping.items():
        if len(o.encode()) != len(n.encode()):
            raise SystemExit("length mismatch: %r (%d) vs %r (%d)"
                             % (o, len(o), n, len(n)))
    return mapping


def parse_pool(data):
    off = 8
    pool = None
    while off + 8 <= len(data):
        ctype, _hsize, csize = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:          # RES_STRING_POOL_TYPE
            pool = off
            break
        if csize == 0:
            break
        off += csize
    if pool is None:
        raise SystemExit("no string pool in manifest")
    _, hsize, _csize, scount, _stycount, flags, sstart, _stystart = \
        struct.unpack_from("<HHIIIIII", data, pool)
    return pool, hsize, scount, bool(flags & 0x100), pool + sstart


def entry(data, pool, hsize, index, utf8, base):
    o = struct.unpack_from("<I", data, pool + hsize + index * 4)[0]
    q = base + o
    if utf8:
        ln = data[q]
        if ln & 0x80:
            ln = ((ln & 0x7F) << 8) | data[q + 1]
            q += 2
        else:
            q += 1
        return bytes(data[q:q + ln]).decode("utf-8", "replace"), q, ln
    ln = struct.unpack_from("<H", data, q)[0]
    q += 2
    if ln & 0x8000:
        ln = ((ln & 0x7FFF) << 16) | struct.unpack_from("<H", data, q)[0]
        q += 2
    return bytes(data[q:q + ln * 2]).decode("utf-16-le", "replace"), q, ln * 2


def patch_manifest(raw, mapping):
    pool, hsize, scount, utf8, base = parse_pool(raw)
    buf = bytearray(raw)
    replaced = 0
    seen = set()
    for i in range(scount):
        s, q, ln = entry(raw, pool, hsize, i, utf8, base)
        if s not in mapping:
            continue
        nb = mapping[s].encode("utf-8" if utf8 else "utf-16-le")
        if len(nb) != ln:
            raise SystemExit("encoded length mismatch for %r" % s)
        buf[q:q + ln] = nb
        replaced += 1
        seen.add(s)
    return bytes(buf), replaced, set(mapping) - seen


def rewrite_apk(src, dst, manifest):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "AndroidManifest.xml":
                data = manifest
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    apk, mapfile, out = sys.argv[1:]
    with open(mapfile, encoding="utf-8") as fh:
        mapping = build_mapping(json.load(fh))
    with zipfile.ZipFile(apk) as z:
        raw = z.read("AndroidManifest.xml")
    patched, count, missing = patch_manifest(raw, mapping)
    rewrite_apk(apk, out, patched)
    print("%s: replaced %d string(s); %d not present"
          % (apk, count, len(missing)))
    for m in sorted(missing):
        print("  not present:", m)


if __name__ == "__main__":
    main()
