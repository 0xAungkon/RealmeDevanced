# Patcher helpers

These scripts implement the byte-level parts of
`../PATCH-RECIPE-realme-link-5.8.md`. They are deliberately dependency-free
(Python standard library only).

## Order of operations

1. Decode the original base once and apply the smali patches (see recipe §3):
   ```bash
   apktool d -s -f -o apktool-out base.apk
   # edit smali...
   apktool b apktool-out -o base-mod.apk
   ```
2. Build the final base from the **original** APK, swapping only the patched dex:
   ```bash
   python3 swap_dex.py base.apk base-mod.apk base-swapped.apk
   ```
3. Rename package/authorities in base + every split:
   ```bash
   python3 patch_axml.py base-swapped.apk patch_map.json base.patched.apk
   for f in split_*.apk; do
     python3 patch_axml.py "$f" patch_map.json "out/$f"
   done
   ```
4. Optional resource strings (label / virtual count):
   ```bash
   python3 patch_arsc.py out/base.patched.apk arsc_map.json out/base.final.apk
   ```
5. Align and sign all APKs with one key:
   ```bash
   zipalign -p -f 4 out/base.final.apk out/base.aligned.apk
   apksigner sign --ks rea-mod.keystore --ks-key-alias rea-mod \
       --out out/base.apk out/base.aligned.apk
   for f in out/split_*.apk; do
     zipalign -p -f 4 "$f" "$f.aligned"
     apksigner sign --ks rea-mod.keystore --ks-key-alias rea-mod \
         --out "$f" "$f.aligned"
   done
   ```
6. Install:
   ```bash
   adb install-multiple -r out/base.apk out/split_*.apk
   adb shell cmd package compile -m speed -f com.realme.mods
   ```

## Notes

- Every replacement must keep the same encoded byte length. Both scripts
  abort on a mismatch.
- `patch_axml.py` only rewrites exact string-pool entries. Component class
  names and broadcast action strings are untouched.
- `build_clone_split.py` is the original end-to-end harness used for this
  build; it combines swap + manifest patch + zipalign + sign.
- Never commit the signing password or the official APK.
