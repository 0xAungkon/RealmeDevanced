import os, shutil, struct, subprocess, sys, zipfile

OLD = 'com.realme.link'
NEW = 'com.realme.mods'
assert len(OLD) == len(NEW)

AUTHS = """AcAccountBaseSdkInitProvider account.ams.AcAccountSDKInitProvider
account.open.sdk.AcOpenDBContentProvider androidx-startup bg_process EventContentProvider
FacebookInitProvider fileprovider firebaseinitprovider hybrid.imageCrop
MicrosoftCognitiveServicesSpeech.InternalContentProvider MyDeviceAppProvider mydevice.shortcuts
MyProcessProvider oplus.epona provider share_preference telecomprovider Track.BalanceEventProvider
Track.MultiProcessSharedPreferencesProvider Track.TrackCommonProvider Track.TrackEventProvider
TuyaMonitorProcessInitContentProvider TuyaOptimusProvider ty_share.provider
UCAccountSDKInitProvider verify_sys_fileprovider WebPro.fileProvider""".split()

MAPPING = {OLD: NEW}
for a in AUTHS:
    MAPPING[OLD + '.' + a] = NEW + '.' + a
MAPPING[OLD + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'] = \
    NEW + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
MAPPING['com.oplus.statistics.provider'] = 'com.realme.mods.oplus.stats.s'
MAPPING['com.facebook.app.FacebookContentProvider589687081742846'] = \
    'com.realme.modss.FacebookContentProvider589687081742846'

for old, new in MAPPING.items():
    if len(old) != len(new):
        raise SystemExit('length mismatch: %r %r' % (old, new))

BASE = '/tmp/pulled-realme-link/base.apk'
MOD = '/tmp/base-mod3.apk'
SPLITS = sorted(
    p for p in os.listdir('/tmp/pulled-realme-link')
    if p.startswith('split_') and p.endswith('.apk'))
OUT = '/tmp/clone-split-out'
SIGNED = '/tmp/clone-split-signed'
KS = '/home/joy/.local/opt/rea-mod.keystore'
PASS = open('/home/joy/.local/opt/rea-mod.pass').read().strip()

for d in (OUT, SIGNED):
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)


def patch_manifest(data):
    off = 8
    pool = None
    while off + 8 <= len(data):
        ctype, hsize, csize = struct.unpack_from('<HHI', data, off)
        if ctype == 1:
            pool = off
            break
        if csize == 0:
            break
        off += csize
    if pool is None:
        raise RuntimeError('no string pool')
    _, hsize, csize, scount, stycount, flags, sstart, stystart = \
        struct.unpack_from('<HHIIIIII', data, pool)
    utf8 = bool(flags & 0x100)
    base = pool + sstart
    buf = bytearray(data)
    replaced = 0
    for i in range(scount):
        o = struct.unpack_from('<I', buf, pool + hsize + i * 4)[0]
        q = base + o
        if utf8:
            ln = buf[q]
            if ln & 0x80:
                ln = ((ln & 0x7f) << 8) | buf[q + 1]
                q += 2
            else:
                q += 1
            s = bytes(buf[q:q + ln]).decode('utf-8', 'replace')
        else:
            ln = struct.unpack_from('<H', buf, q)[0]
            q += 2
            if ln & 0x8000:
                ln = ((ln & 0x7fff) << 16) | struct.unpack_from('<H', buf, q)[0]
                q += 2
            s = bytes(buf[q:q + ln * 2]).decode('utf-16-le', 'replace')
            ln = ln * 2
        if s in MAPPING:
            nb = MAPPING[s].encode('utf-8' if utf8 else 'utf-16-le')
            if len(nb) != ln:
                raise RuntimeError('encoded length mismatch ' + s)
            buf[q:q + ln] = nb
            replaced += 1
    return bytes(buf), replaced


def build(src, dst, swap=None):
    zin = zipfile.ZipFile(src)
    zmod = zipfile.ZipFile(MOD) if swap else None
    total = 0
    with zipfile.ZipFile(dst, 'w') as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if swap and item.filename in swap:
                data = zmod.read(item.filename)
            if item.filename == 'AndroidManifest.xml':
                data, total = patch_manifest(data)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zout.writestr(zi, data)
    return total


def align_sign(src, dst):
    aligned = dst + '.aligned'
    subprocess.run(['zipalign', '-p', '-f', '4', src, aligned], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(['apksigner', 'sign', '--ks', KS, '--ks-pass', 'pass:' + PASS,
                    '--key-pass', 'pass:' + PASS, '--ks-key-alias', 'rea-mod',
                    '--out', dst, aligned], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.remove(aligned)


print('base: swapping classes36/38 + manifest')
n = build(BASE, os.path.join(OUT, 'base.apk'),
          swap={'classes36.dex', 'classes38.dex'})
print('  base string replacements:', n)
align_sign(os.path.join(OUT, 'base.apk'), os.path.join(SIGNED, 'base.apk'))

for name in SPLITS:
    src = os.path.join('/tmp/pulled-realme-link', name)
    tmp = os.path.join(OUT, name)
    n = build(src, tmp)
    print('%-52s replacements=%d' % (name, n))
    align_sign(tmp, os.path.join(SIGNED, name))

print('done ->', SIGNED)
