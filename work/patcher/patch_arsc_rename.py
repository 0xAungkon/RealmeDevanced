#!/usr/bin/env python3
"""Rename a string in resources.arsc even when the new text has a different
length.

Unlike patch_arsc.py (equal-length only), this rewrites the *global* string
pool: it replaces one pool entry with a longer/shorter value and fixes up every
following offset, the pool chunk size, the styles start, and the ResTable size.
No resource rebuild is performed, so the extra resource packages (0x80-0x84)
are preserved.

Only the global string pool is edited. All other chunks simply shift.

Usage:
  patch_arsc_rename.py <in.apk> <out.apk> <old-string> <new-string>
"""
import struct
import sys
import zipfile

ARSC = 'resources.arsc'


def decode_len(buf, q):
    n = buf[q]
    if n & 0x80:
        return ((n & 0x7F) << 8) | buf[q + 1], q + 2
    return n, q + 1


def encode_len(n):
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def read_string(buf, utf8, start):
    """Return (text, end) for the string at absolute offset `start`."""
    q = start
    if utf8:
        _, q = decode_len(buf, q)          # UTF-16 length (code units)
        n, q = decode_len(buf, q)          # UTF-8 length (bytes)
        text = bytes(buf[q:q + n]).decode('utf-8', 'replace')
        q += n + 1                         # + NUL
    else:
        n, q = decode_len(buf, q)          # UTF-16 length (code units)
        text = bytes(buf[q:q + n * 2]).decode('utf-16-le', 'replace')
        q += n * 2 + 2                     # + NUL
    return text, q


def encode_utf8(text):
    raw = text.encode('utf-8')
    units = len(text.encode('utf-16-le')) // 2
    return encode_len(units) + encode_len(len(raw)) + raw + b'\x00'


def encode_utf16(text):
    raw = text.encode('utf-16-le')
    units = len(raw) // 2
    return encode_len(units) + raw + b'\x00\x00'


def patch_arsc(data, old, new):
    if len(data) < 12:
        raise SystemExit('arsc too small')
    ctype, header_size, total, pkgs = struct.unpack_from('<HHII', data, 0)
    if ctype != 0x0002:
        raise SystemExit('not a ResTable')

    po = header_size
    ptype, phdr, psize, scount, stycount, flags, sstart, stystart = \
        struct.unpack_from('<HHIIIIII', data, po)
    if ptype != 0x0001:
        raise SystemExit('no global string pool')
    utf8 = bool(flags & 0x100)
    base = po + sstart

    offsets = [struct.unpack_from('<I', data, po + phdr + 4 * i)[0]
               for i in range(scount)]
    texts = [read_string(data, utf8, base + off)[0] for off in offsets]

    hits = [i for i, s in enumerate(texts) if s == old]
    if len(hits) != 1:
        raise SystemExit('expected exactly one %r, found %d at %s'
                         % (old, len(hits), hits[:10]))
    idx = hits[0]

    start = base + offsets[idx]
    _, end = read_string(data, utf8, start)
    new_enc = encode_utf8(new) if utf8 else encode_utf16(new)
    delta = len(new_enc) - (end - start)

    # New offsets: entries after idx shift by delta. Entries before idx stay.
    off_arr = bytearray(data[po + phdr:po + sstart])
    for j in range(idx + 1, scount):
        val = struct.unpack_from('<I', off_arr, 4 * j)[0] + delta
        struct.pack_into('<I', off_arr, 4 * j, val)

    strings_region = bytearray(data[po + sstart:po + stystart])
    rel_start = offsets[idx]
    rel_end = rel_start + (end - start)
    strings_region[rel_start:rel_end] = new_enc

    # Keep the styles block 4-byte aligned after the size change.
    pad = (4 - ((len(strings_region)) % 4)) % 4
    strings_region.extend(b'\x00' * pad)

    styles_region = data[po + stystart:po + psize]

    body = bytes(off_arr) + bytes(strings_region) + styles_region
    new_stystart = sstart + len(strings_region)
    new_pool = struct.pack('<HHIIIIII', 1, phdr, phdr + len(body),
                           scount, stycount, flags, sstart, new_stystart) + body

    tail = data[po + psize:]
    out = bytearray(data[:po] + new_pool + tail)
    struct.pack_into('<I', out, 4, len(out))
    return bytes(out)


def main(src, dst, old, new):
    with zipfile.ZipFile(src) as zin:
        with zipfile.ZipFile(dst, 'w') as zout:
            done = False
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == ARSC:
                    data = patch_arsc(data, old, new)
                    done = True
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zi.internal_attr = item.internal_attr
                zi.create_system = item.create_system
                zout.writestr(zi, data)
    if not done:
        raise SystemExit('resources.arsc not found in ' + src)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
