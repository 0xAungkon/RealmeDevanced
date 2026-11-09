#!/usr/bin/env python3
"""Patch res/drawable/setting_img.xml (0x7f080913) inside a base.apk.

The vector has two paths:
  1) green circle   fillColor 0xff78da59  -> transparent (0x00000000)
  2) white gear     fillColor 0xffffffff  -> black       (0xff000000)

Both are 4-byte TYPE_INT_COLOR_RGB8 (0x1d) values inside the compiled AXML.
The type byte is changed to ARGB8 (0x1c) so the zero alpha is honoured.
Equal-length binary patch: no resource rebuild needed.

Usage: patch_setting_img.py <base.apk> <out.apk>
"""
import struct
import sys
import zipfile

ENTRY = 'res/drawable/setting_img.xml'

# (offset of typed data, offset of dataType byte)
CIRCLE = (1716, 1715)
GEAR = (1816, 1815)


def patch_xml(data):
    buf = bytearray(data)
    for off, type_off in (CIRCLE, GEAR):
        assert struct.unpack_from('<I', buf, off)[0] in (0xFF78DA59, 0xFFFFFFFF)
        assert buf[type_off] == 0x1D
        buf[type_off] = 0x1C
    struct.pack_into('<I', buf, CIRCLE[0], 0x00000000)
    struct.pack_into('<I', buf, GEAR[0], 0xFF000000)
    return bytes(buf)


def main(src, dst):
    zin = zipfile.ZipFile(src)
    with zipfile.ZipFile(dst, 'w') as zout:
        done = False
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == ENTRY:
                data = patch_xml(data)
                done = True
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
    if not done:
        raise SystemExit('entry not found: ' + ENTRY)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
