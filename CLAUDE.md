# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A reverse-engineering workspace for **realme Link 5.8.811.2814** (split APK bundle).
The purpose is to **deVance** the app — strip the noise (account walls, promos,
upsell cards, consent/legal interruptions, guides, nags, dead space) while keeping
local device control. The deliverable is a working, personal-use clone installed
as **`com.realme.devanced`** (launcher label "Realme Link DeVanced") that can coexist
with the official `com.realme.link`. The two Markdown files are the real product:
every change is documented so it can later be re-implemented in an on-device
patcher APK.

Tracked: the docs, dependency-free Python helpers, and the Java source of the
on-device patcher (`patcher-app/`). Decoded APKs, jadx outputs, build artifacts,
target APKs and the patcher's embedded dex payload are gitignored and
regenerable.
`rea-src/` is a local clone of the REA MCP tool source, not part of the
deliverable. `app/com.realme.link_1.2.105.18-323....apk` is an obsolete
monolithic build kept only as a reference — all findings target the installed
5.8 split bundle.

- `work/PATCH-RECIPE-realme-link-5.8.md` — canonical patch catalog (§3.1–§3.24),
  build pipeline, patcher design, pitfalls. **Read this before touching anything.**
- `work/MAP-realme-link-5.8.md` — app-shell map: startup, tabs, WebView bridge,
  telemetry, ads, permissions, update path, integrity checks.
- `work/patcher/README.md` — order of operations for the byte-level helpers.

## Environment and artifacts

Toolchain lives in `~/.local/opt` / `~/.local/bin`: JDK 21, apktool 3.0.3,
jadx 1.5.6, Android build-tools 35 (`aapt2`, `apksigner`, `zipalign`).
Signing key: `~/.local/opt/rea-mod.keystore`, alias `rea-mod`, password read from
`~/.local/opt/rea-mod.pass` — never commit the password.

Paths (not tracked):

| Path | Meaning |
|---|---|
| `/tmp/pulled-realme-link/` | original base + 14 splits pulled from the device |
| `work/base58/apktool-out/` | decoded/patched tree (smali, res, assets) |
| `work/base58/jadx-{0,35,38}/sources/` | targeted Java for the shell dexes |
| `/tmp/clone-devanced-signed/` | current signed split set (package `com.realme.devanced`) |
| `/tmp/base-devanced.apk` | current patched+renamed base (latest installed) |
| test device | Infinix X6831, Android 13, via adb |

There is no test suite or linter. Verification is on-device (see below).

## Build / install pipeline

The critical rule: **`apktool b` is only used to harvest dex files**. Its
resources are discarded because aapt2 drops the shared resource packages
(`0x80`–`0x84`). The final APK is always assembled from the original base with
only `classes*.dex` swapped in, and manifests/`resources.arsc` patched as bytes.

```bash
# 1. decode once
apktool d -s -f -o work/base58/apktool-out /tmp/pulled-realme-link/base.apk

# 2. edit smali under work/base58/apktool-out/smali*/ ; then rebuild dex
apktool b work/base58/apktool-out -o /tmp/base-mod-build.apk

# 3. swap only the patched dex into the current (already renamed) base
python3 -I work/patcher/swap_dex.py /tmp/base-devanced.apk /tmp/base-mod-build.apk \
    /tmp/base-mod-new-unsigned.apk classes38.dex
# (classes36.dex = account/login, classes38.dex = shell, classes40.dex = headset/device)

# 4. align + sign with the shared key
zipalign -p -f 4 /tmp/base-mod-new-unsigned.apk /tmp/base-mod-new-aligned.apk
apksigner sign --ks ~/.local/opt/rea-mod.keystore \
    --ks-pass "pass:$(cat ~/.local/opt/rea-mod.pass)" \
    --key-pass "pass:$(cat ~/.local/opt/rea-mod.pass)" \
    --ks-key-alias rea-mod --out /tmp/base-mod-new.apk /tmp/base-mod-new-aligned.apk

# 5. install the whole bundle (all splits must share the same cert); -r preserves data
adb shell am force-stop com.realme.devanced
adb install-multiple -r --no-incremental /tmp/base-mod-new.apk \
    /tmp/clone-devanced-signed/split_*.apk
adb shell cmd package compile -m speed -f com.realme.devanced   # ~4.5s cold start -> ~1.5s

# 6. verify
adb exec-out screencap -p > /tmp/shot.png
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
adb logcat -b crash | grep com.realme.devanced
adb shell pidof com.realme.devanced
aapt2 dump badging /tmp/base-mod-new.apk | grep -E "package:|application-label:"
```

## Patcher helpers (`work/patcher/`, stdlib only)

| Script | Job |
|---|---|
| `patch_axml.py` | equal-length string-pool edits in binary `AndroidManifest.xml` (package/authorities), base + every split |
| `patch_axml_rename.py` | **variable-length** package-prefix rename: rebuilds the manifest string pool, offsets and XML header |
| `patch_arsc.py` | equal-length string edits in `resources.arsc` |
| `patch_arsc_rename.py` | **variable-length** rename by resizing the global string pool (fixes offsets/styles/table size) |
| `swap_dex.py` | replace `classes*.dex` entries in a base APK |
| `patch_setting_img.py` | vector color patch in `res/drawable/setting_img.xml` |
| `build_clone_split.py` | original end-to-end harness (swap + manifest + align + sign) |

Run them with `python3 -I` (they read untrusted APKs). Same-length replacement is
required by `patch_axml.py` / `patch_arsc.py`; use `patch_axml_rename.py` for a
longer/shorter package name and `patch_arsc_rename.py` for resource strings.

## Clone model and constraints

- Package rename: `com.realme.link` → `com.realme.devanced` is longer, so the
  manifest string pool is resized by `patch_axml_rename.py`; authorities and the
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are renamed in base and every split.
  The equal-length path (`patch_axml.py`, e.g. `com.realme.mods`) is kept for
  reference but is no longer the current identity.
- All 15 APKs must be signed by one cert or `install-multiple` fails. Re-signing
  requires uninstalling the official build, and `allowBackup=false` loses data.
- Do **not** merge the split bundle into one APK: dynamic resource packages
  (`0x80`–`0x84`, e.g. `com.realme.link.realmeHeadset`) stop resolving.
- The clone works without the headset dynamic module because `LinkApplication`
  calls `AssetManager.addAssetPath()` on the base APK, and headset login gates are
  bypassed.
- The clone is intentionally account-free: local BLE/device controls work; cloud
  sync, mall, messages and health sync cannot authenticate.

Shell layout: `MainActivity` (classes38) hosts `MainFragment` (home) and
`UserFragment`; the Me tab was replaced by a Settings button that opens
`SysSettingsActivity`; `AboutLinkActivity` is the About page; the headset/device
activities and TL connection SDK are in classes40, while the shell-side manager
factories/delegates are in classes38.

## Runtime gotchas that have already caused crashes or failed patches

1. `Fragment.findViewById` does not exist in this AndroidX build → use
   `getView().findViewById`.
2. **ConstraintLayout keeps its own `mMinHeight`.** `View.setMinimumHeight()`
   does not clear `android:minHeight`; call
   `ConstraintLayout.setMinHeight(int)` on the view.
3. `LottieAnimationView.cancelAnimation()` is absent → `NoSuchMethodError`.
   `setImageResource()` works.
4. "Crash on device click" was a **task restart**: `DeviceUtils.w(Context)` sends
   `MAIN | CLEAR_TASK`. Check `ActivityTaskManager`/`exit-info`, not just
   `FATAL EXCEPTION`.
5. Hide header views in `onResume()`/`onVisible()`, not `initViews()` — the
   included `layout_home_header` may not be attached yet.
6. For direct children of a `LinearLayout`, use `GONE` (not `INVISIBLE`) to
   remove the space as well.
7. Hiding ConstraintLayout chain members can make the remaining anchors collapse
   and overlap (this happened on About). Hide the leftover divider/rows too, or
   re-anchor via the public `ConstraintLayout$LayoutParams.topToBottom` /
   `topToTop` fields.
8. Disabling `PhoneModeKt.d(Activity)` removes the floating phone-mode window
   globally (`810x1800`, `dimAmount 0.8`, `FLAG_DIM_BEHIND`, rounded background).
9. AOT compile after install (`cmd package compile -m speed`) is needed for a
   usable cold start.

## On-device patcher (`patcher-app/`)

A no-root Java app (built without Gradle) that reads the installed realme Link
bundle, swaps in the patched `classes36/37/38/40.dex`, renames the package to
`com.realme.devanced`, re-signs base + splits and installs via
`PackageInstaller`. Build with `BASE_APK=/tmp/base-devanced.apk
./patcher-app/build.sh` → `patcher-app/build/RealmeVancedPatcher_<version>.apk`.

- `classes37.dex` must be part of the payload: it contains the
  `PhoneModeKt.d()` early return (§3.21) that turns every phone-mode activity
  full-screen. Without it Device Detail, EQ Mode, Dual Connection, Find my
  phone and Button Settings keep the floating `810x1800` window.
- `AxmlRename` / `ArscRename` resize string pools; `ZipRewriter` must keep
  `resources.arsc` 4-byte aligned and `.so` entries STORED/4 KiB aligned or
  PackageManager rejects the APK (`Failed parse during installPackageLI`).
- The built APK embeds derivative realme dex: personal use only, never commit
  or publish it. Only `patcher-app/src`, the manifest and `build.sh` are tracked.
- After a successful install the UI shows “Installed as com.realme.devanced.
  Now you can delete the original realme Link if you want.” The result is
  persisted in SharedPreferences because the installer may background the app.

## Git

`development` is the working branch; `main` is the released/latest branch
(fast-forwarded from `development`). Commit docs and patcher scripts only —
never decoded artifacts, target APKs, keystores or passwords.
