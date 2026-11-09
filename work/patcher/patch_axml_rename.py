#!/usr/bin/env python3
"""Rename a package prefix inside a binary AndroidManifest.xml, even when the
new text has a different length.

The previous clone rename ("com.realme.link" -> "com.realme.mods") relied on
equal-length replacement.  A longer package name ("com.realme.devanced") needs
the string pool resized instead.  This script rebuilds the XML string pool,
recalculates every string offset, keeps the style block 4-byte aligned and
updates the XML file header size.  Attribute name/value indices are unchanged,
so the XML tree itself is untouched.

Every pool string that equals `old` or starts with `old` is rewritten with the
same prefix replaced by `new` (covers provider authorities and the
DYNAMIC_RECEIVER permission).

Usage:
  patch_axml_rename.py <in.apk> <out.apk> <old-prefix> <new-prefix>

Apply it to the base APK and to every split APK.
"""
import struct
import sys
import zipfile


def decode_len16(buf, q):
    n = struct.unpack_from('<H', buf, q)[0]
    if n & 0x8000:
        m = struct.unpack_from('<H', buf, q + 2)[0]
        return ((n & 0x7FFF) << 16) | m, q + 4
    return n, q + 2


def encode_len16(n):
    if n > 0x7FFF:
        return struct.pack('<HH', 0x8000 | (n >> 16), n & 0xFFFF)
    return struct.pack('<H', n)


def decode_len8(buf, q):
    n = buf[q]
    if n & 0x80:
        return ((n & 0x7F) << 8) | buf[q + 1], q + 2
    return n, q + 1


def encode_len8(n):
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def read_string(buf, utf8, start):
    q = start
    if utf8:
        _, q = decode_len8(buf, q)       # UTF-16 length
        n, q = decode_len8(buf, q)       # UTF-8 byte length
        text = bytes(buf[q:q + n]).decode('utf-8', 'replace')
        return text, q + n + 1
    n, q = decode_len16(buf, q)          # UTF-16 length
    text = bytes(buf[q:q + n * 2]).decode('utf-16-le', 'replace')
    return text, q + n * 2 + 2


def encode_string(text, utf8):
    if utf8:
        raw = text.encode('utf-8')
        units = len(text.encode('utf-16-le')) // 2
        return encode_len8(units) + encode_len8(len(raw)) + raw + b'\x00'
    raw = text.encode('utf-16-le')
    return encode_len16(len(raw) // 2) + raw + b'\x00\x00'


def rename(text, old, new):
    if text == old:
        return new
    if text.startswith(old):
        return new + text[len(old):]
    return text


def patch_axml(data, old, new):
    if len(data) < 8:
        raise SystemExit('too small to be binary XML')
    ftype, fhdr, fsize = struct.unpack_from('<HHI', data, 0)
    if ftype != 0x0003:
        raise SystemExit('not an XML chunk (type 0x%04x)' % ftype)

    po = fhdr
    ptype, phdr, psize, scount, stycount, flags, sstart, stystart = \
        struct.unpack_from('<HHIIIIII', data, po)
    if ptype != 0x0001:
        raise SystemExit('first chunk is not a string pool')
    utf8 = bool(flags & 0x100)

    old_strings = []
    for i in range(scount):
        off = struct.unpack_from('<I', data, po + phdr + 4 * i)[0]
        old_strings.append(read_string(data, utf8, po + sstart + off)[0])
    new_strings = [rename(s, old, new) for s in old_strings]

    if new_strings == old_strings:
        return data

    offsets = []
    blob = bytearray()
    for s in new_strings:
        offsets.append(len(blob))
        blob.extend(encode_string(s, utf8))
    pad = (4 - len(blob) % 4) % 4
    blob.extend(b'\x00' * pad)

    style_offsets = data[po + phdr + 4 * scount:
                        po + phdr + 4 * (scount + stycount)]
    styles_data = data[po + stystart:po + psize] if stycount else b''

    body = b''.join(struct.pack('<I', off) for off in offsets)
    body += style_offsets + bytes(blob) + styles_data
    strings_start = phdr + 4 * scount + 4 * stycount
    styles_start = strings_start + len(blob) if stycount else 0
    new_pool = struct.pack('<HHIIIIII', 1, phdr,
                           phdr + len(body), scount, stycount,
                           flags, strings_start, styles_start) + body

    out = bytearray(data[:po] + new_pool + data[po + psize:])
    struct.pack_into('<I', out, 4, len(out))
    return bytes(out)


def main(src, dst, old, new):
    with zipfile.ZipFile(src) as zin:
        with zipfile.ZipFile(dst, 'w') as zout:
            done = False
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == 'AndroidManifest.xml':
                    data = patch_axml(data, old, new)
                    done = True
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zi.internal_attr = item.internal_attr
                zi.create_system = item.create_system
                zout.writestr(zi, data)
    if not done:
        raise SystemExit('AndroidManifest.xml not found in ' + src)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
