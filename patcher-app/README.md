# RealmeVanced Patcher (local build)

An on-device, no-root patcher for **realme Link 5.8.811.2814**.

It reads the realme Link APKs already installed on the device, rebuilds them as
the de-noised **`com.realme.devanced`** clone, re-signs the whole split set with
the embedded personal key, and installs the result through a
`PackageInstaller` session. The official `com.realme.link` stays installed.

After a successful install the patcher shows:

> Installed as com.realme.devanced.
>
> **Now you can delete the original realme Link if you want.**

## ⚠ Personal use only

The built APK embeds the patched `classes36/38/40.dex` from a locally built
clone. Those are derivative works of realme's proprietary software, so the
resulting APK must **not** be published, shared or attached to a release. The
repository tracks only the patcher source; the payload is generated locally at
build time and ignored by git.

## What it does

1. Reads `com.realme.link` (`sourceDir` + `splitSourceDirs`) and verifies
   version `5.8.811.2814`.
2. Copies the installed split bundle into app-private cache.
3. Replaces `classes36.dex`, `classes38.dex`, `classes40.dex` with the patched
   variants bundled in `assets/`.
4. Renames the base and every split manifest from `com.realme.link` to
   `com.realme.devanced` (resized AXML string pool; authorities and the
   dynamic-receiver permission included).
5. Renames the launcher label to **Realme Link DeVanced** and patches
   `setting_img.xml` to a black gear with no circle.
6. Rebuilds the zips while keeping `resources.arsc` 4-byte aligned and native
   libraries `STORED`/4 KiB aligned.
7. Signs base + splits with the embedded `rea-mod` key (v1/v2/v3).
8. Installs the signed set as `com.realme.devanced`.

## Build

```bash
# payload: classes36/38/40.dex from a locally built devanced base
BASE_APK=/tmp/base-devanced.apk ./build.sh
# -> build/RealmeVancedPatcher_v5.8.811.2814.apk
```

Requirements: Android SDK build-tools 35 + platform 35, JDK 21, and the local
signing key at `~/.local/opt/rea-mod.keystore` (+ password file). No Gradle is
used; `build.sh` runs javac → d8 → aapt2 → zipalign → apksigner.

## Notes

- New package means fresh app data; the system BLE bond survives, so the device
  is picked up again without re-pairing.
- The installer may update an existing `com.realme.devanced` in place; it is
  signed with the same key.
- Package-manager parsing fails if a rebuilt APK has `resources.arsc`
  compressed or unaligned — `ZipRewriter` aligns every STORED entry.
