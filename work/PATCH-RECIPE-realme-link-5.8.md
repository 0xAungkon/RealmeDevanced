# realme Link 5.8.811.2814 — complete patch recipe

Purpose: document every change made to produce the working `com.realme.devanced`
clone, in enough detail to re-implement it as an automated on-device patcher.

Everything here is for the user's own device / personal use. The patcher must
not redistribute a re-signed proprietary app.

---

## 1. Target identity

| Field | Value |
|---|---|
| Package | `com.realme.link` |
| Version | `5.8.811.2814` / versionCode `530214` |
| min / target SDK | 26 / 36 |
| Original signer | `CN=ROM, O=realme, EMAILADDRESS=apps_link@realme.com` |
| Original cert SHA-256 | `eb8d65ac4944a775c9995855d0ec61ef53c1eee12db3588057f80e0f27623bb7` |
| Install shape | base + 14 split APKs |
| Split names | `config.arm64_v8a`, `config.xxhdpi`, `realmeBracelet`, `vendorIdoBand`, `vendorLifesenseWatch`, `vendorRyeexWatch`, `vendorTouchGWatch` (+ their `config.*` splits) |
| Test device | Infinix X6831, Android 13 |

Clone identity produced:

| Field | Value |
|---|---|
| Package | `com.realme.devanced` (19 chars; manifest pool resized, §3.25) |
| Label | `Realme Link DeVanced` |
| Signing key | `~/.local/opt/rea-mod.keystore`, alias `rea-mod` (do not commit the password) |
| Cert SHA-256 | `98719857b36d95767f7f319e42e8752e2e44a78dbe10b6907ff386724093c231` |

History: the first clone used `com.realme.mods`, deliberately the same byte
length as `com.realme.link` so AXML string-pool offsets stayed stable and could
be patched without rebuilding resources. The package was later renamed to
`com.realme.devanced`, which is longer, so the manifest string pool is now
resized by `patch_axml_rename.py` (§3.25). `com.realme.mods` remains supported
by the equal-length patcher for reference.

---

## 2. Toolchain / artifacts

- JDK 21 (`~/.local/opt/jdk`)
- apktool 3.0.3 (`~/.local/opt/apktool.jar`)
- jadx 1.5.6 (`~/.local/opt/jadx`)
- Android build-tools 35: `aapt2`, `apksigner`, `zipalign`
- APKEditor 1.4.9 (`~/.local/opt/APKEditor.jar`) — used only for the failed
  merge experiment
- Original pulled bundle: `/tmp/pulled-realme-link/`
- Decoded/patched tree: `/home/joy/Desktop/reverse-eng/work/base58/apktool-out/`
- Final signed split clone: `/tmp/clone-split-signed/`
- Reusable scripts: `work/patcher/`

---

## 3. Patch catalog

Dex ownership (original 40-dex base):

| Dex | Contents touched |
|---|---|
| `classes36.dex` | `com.realme.iot.account.*` |
| `classes38.dex` | `com.realme.link.*` app/shell |
| `classes40.dex` | `com.realme.iot.headset.*` |

All patches below were applied to the apktool smali tree, rebuilt, and the
resulting `classes36/38/40.dex` swapped into the **original** base APK (see §4).
Never rebuild the base resources with aapt2: it drops the extra resource
packages (`0x80`–`0x84`).

### 3.1 Login removal — `com.realme.iot.account.internal.RealAccountHelper`

File: `smali_classes36/com/realme/iot/account/internal/RealAccountHelper.smali`

| Method | Original behavior | Patch |
|---|---|---|
| `C(Landroid/content/Context;)V` | builds `Intent(context, LoginActivity)` | build `Intent(context, com.realme.link.home.MainActivity)`, add flags `0x14000000` (`NEW_TASK|CLEAR_TOP`) |
| `c(Landroid/content/Context;)V` | starts `RealAccountHelper$reLogin$1` coroutine (re-login fragment) | body → `.locals 0` / `return-void` |
| `p(Landroid/content/Context;)V` | starts `tipUserToReLogin` coroutine (dialog) | body → `.locals 0` / `return-void` |
| `z(Landroid/app/Activity;)V` | shows `NxDialog` login prompt | body → `.locals 0` / `return-void` |

Patch text for `C`:

```smali
new-instance v0, Landroid/content/Intent;
const-class v1, Lcom/realme/link/home/MainActivity;
invoke-direct {v0, p1, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
const/high16 v1, 0x14000000
invoke-virtual {v0, v1}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;
invoke-virtual {p1, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
return-void
```

### 3.2 Home cards + virtual strip — `com.realme.link.home.MainFragment`

File: `smali_classes38/com/realme/link/home/MainFragment.smali`

**a) `initViews()V` — hide Smart AI and Health trends cards**

Register values at this point: `v1 = 0x0` (VISIBLE), `v3 = 0x8` (GONE).

| View | Binding field | Patch |
|---|---|---|
| `cl_ai` (`0x7f0a01e5`) | `FragmentMainBinding.k` | visible branch `setVisibility(v1)` → `setVisibility(v3)` |
| `cl_health_trend` (`0x7f0a01ee`) | `FragmentMainBinding.m` | visible branch `setVisibility(v1)` → `setVisibility(v3)` |

**b) `F1(Ljava/util/List;)V` — hide Virtual experience strip**

Registers in this method: `v2 = 0x8` (GONE), `v3 = 0x0` (VISIBLE).

Views: `rl_virtual_device_title` (`.D`), title `TextView` (`.K`),
`recycle_view_virtual_device` (`.A`).

All six `setVisibility` calls (non-empty and empty branches) must pass `v2`:

```smali
iget-object vX, vX, Lcom/realme/databinding/FragmentMainBinding;->D:Landroid/widget/RelativeLayout;
invoke-virtual {vX, v2}, Landroid/view/View;->setVisibility(I)V
iget-object vX, vX, Lcom/realme/databinding/FragmentMainBinding;->K:Landroid/widget/TextView;
invoke-virtual {vX, v2}, Landroid/view/View;->setVisibility(I)V
iget-object vX, vX, Lcom/realme/databinding/FragmentMainBinding;->A:Landroidx/recyclerview/widget/RecyclerView;
invoke-virtual {vX, v2}, Landroid/view/View;->setVisibility(I)V
```

Historical note: an earlier attempt swapped the wrong registers and made the
empty branch visible; verify `v2 = 0x8` at method start.

**c) Pair-device login gate**

Click handler switch (`:sswitch_3`, case IDs `0x7f0a0513` / `0x7f0a0936`):

```smali
invoke-static {}, Lcom/realme/link/cache/CacheHelper;->n()Z
move-result p1
if-eqz p1, :cond_1        # ← remove this line
const-class p1, Lcom/realme/link/devices/scan/SearchDeviceActivity;
invoke-virtual {p0, p1}, Lcom/realme/iot/common/mvp/BaseAbstractFragment;->startActivity(Ljava/lang/Class;)V
return-void
:cond_1
... IAccountHelper.f(...) ...
```

Removing the conditional makes “Pair device” always open the scanner.

### 3.3 Register base resources at startup — `com.realme.link.LinkApplication`

File: `smali_classes38/com/realme/link/LinkApplication.smali`

`onCreate()V`: change `.locals 2` → `.locals 5`; inject after
`invoke-super {p0}, Landroid/app/Application;->onCreate()V`:

```smali
invoke-virtual {p0}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;
move-result-object v2
new-instance v3, Ljava/io/File;
invoke-virtual {p0}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
move-result-object v4
iget-object v4, v4, Landroid/content/pm/ApplicationInfo;->sourceDir:Ljava/lang/String;
invoke-direct {v3, v4}, Ljava/io/File;-><init>(Ljava/lang/String;)V
invoke-static {v2, v3}, Lcom/google/android/play/core/splitcompat/zza;->zzc(Landroid/content/res/AssetManager;Ljava/io/File;)I
```

`zza.zzc(AssetManager, File)` is a public static helper in the bundled
Play Core splitcompat (`classes12.dex`); it calls hidden
`AssetManager.addAssetPath` reflectively. This registers the base APK's
secondary resource packages, including `com.realme.link.realmeHeadset`
(package id `0x80`), which are otherwise unresolved.

### 3.4 Bottom bar — remove Health + Workout

File: `smali_classes38/com/realme/link/home/MainActivity.smali`

**a) `onCreate(Bundle)` end**, after the `IAccountHelper.m(this)` call:

```smali
const v0, 0x7f0a0ac9                 # tab_health_all
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_hide_tab_health
const/16 v1, 0x8
invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_tab_health
const v0, 0x7f0a0ad7                 # tab_sport_all
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_hide_tab_sport
const/16 v1, 0x8
invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_tab_sport
return-void
```

`Activity.findViewById` is available; the method already has `.locals 2`.

**b) Sport tab show site** (TAB_SPORT branch):

```smali
iget-object p1, p1, Lcom/realme/databinding/LinkActivityMainBinding;->y:Landroid/widget/RelativeLayout;
const/16 v0, 0x8                       # was const/4 v0, 0x0
invoke-virtual {p1, v0}, Landroid/view/View;->setVisibility(I)V
```

The `initData` equivalent uses `v1`; the end-of-`onCreate` hide covers it.

### 3.5 Me page rows — `com.realme.link.home.user.UserFragment`

File: `smali_classes38/com/realme/link/home/user/UserFragment.smali`

Hidden IDs:

| Row | ID |
|---|---|
| Virtual experience | `0x7f0a039c` (`fictitious_experience`) |
| Voice service | `0x7f0a0e52` (`voice_service`) |
| Scenes | `0x7f0a06c3` (`ll_scene`) |
| My goals | `0x7f0a0729` (`me_target`) |
| Feedback entry | `0x7f0a032c` (`encourage`) |
| Help | `0x7f0a045e` (`help`) |

Inject at the end of both `onResume()V` and `onVisible()V`:

```smali
invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_ov_done
const v1, 0x7f0a039c
invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
move-result-object v2
if-eqz v2, :cond_ov1
const/16 v1, 0x8
invoke-virtual {v2, v1}, Landroid/view/View;->setVisibility(I)V
:cond_ov1
# repeat for 0x7f0a0e52, 0x7f0a06c3, 0x7f0a0729, 0x7f0a032c, 0x7f0a045e
:cond_ov_done
return-void
```

`onResume` needs `.locals 2` → `.locals 3`; `onVisible` already has ≥4 locals.

**Critical**: this app's AndroidX `Fragment` has **no `findViewById`**
(`NoSuchMethodError`); always go through `getView()`.

**Help show site**: `FragmentUser2Binding.m` visible branch changed from
`const/4 v2, 0x0` to `const/16 v2, 0x8`.

### 3.6 Scanner virtual banner — `com.realme.link.devices.scan.SearchDeviceActivity`

File: `smali_classes38/com/realme/link/devices/scan/SearchDeviceActivity.smali`

`onCreate(Bundle)` `onCreate`: `.locals 0` → `.locals 2`; before `return-void`:

```smali
const v0, 0x7f0a096c                 # rl_remind
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_hide_remind
const/16 v1, 0x8
invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_remind
return-void
```

### 3.7 The “device click crash” — it is a self-restart

Live logcat showed no exception. Tapping a detected earbud started
`HeadsetDeviceActivity`, which then started `SplashActivity` with
`0x30008000` (`NEW_TASK|SINGLE_TOP|CLEAR_TASK`), destroying the task.

Cause: `DeviceUtils.w(Context)` is called from the not-logged-in branch.

**a)** `smali_classes40/.../headset/activity/device/HeadsetDeviceActivity.smali`,
inside `onCreate`:

```smali
invoke-static {}, Lcom/realme/iot/common/account/AccountManagerWrapper;->b()Z
move-result v1
goto :cond_7                          # was: if-nez v1, :cond_7
```

**b)** `smali_classes40/.../headset/activity/onespace/HeadsetOneSpaceActivity.smali`,
inside `initData`:

```smali
invoke-virtual {v0}, Lcom/realme/iot/common/account/AccountManagerWrapper$Companion;->b()Z
move-result v0
goto :cond_0                          # was: if-nez v0, :cond_0
```

This skips the `DeviceUtils.w()` + `finish()` branch and lets the page run.
The `addAssetPath` patch in §3.3 is required for its `0x80` resources.

### 3.8 Rating / feedback overlay — `com.realme.link.util.RateUtil`

File: `smali_classes38/com/realme/link/util/RateUtil.smali`

`e(Landroid/app/Activity;Ljava/lang/String;)V` builds the
“How do you like realme Link?” dialog with “Give feedback” / “Love it”.
Called from `MainActivity.onStart` via `RateUtil.c`, and from main/health/user
fragments. Disable at the source by making the method return immediately:

```smali
.method public static e(Landroid/app/Activity;Ljava/lang/String;)V
    .locals 5

    return-void

    # original body left unreachable
    const/4 v0, 0x0
    ...
.end method
```

Dead code is accepted by smali/dalvik. This removes every prompt path.

### 3.9 Resources — label and virtual-device count

Base `resources.arsc` only; same-length byte replacements (no rebuild):

| Old | New | Bytes |
|---|---|---|
| `realme Link` | `realme Mods` | 11 → 11 |
| `%s virtual devices` | 18 spaces | 18 → 18 |
| `%s virtual device` | 17 spaces | 17 → 17 |

`realme Link` occurs 3,083 times in the arsc (labels, legal text, UI copy).
All are rewritten; that is why in-app copy still says “realme Mods”. The
launcher label was later renamed to **Realme Link DeVanced** (§3.23); the body
text strings were intentionally left untouched.

### 3.10 Manifest — clone package and authorities

Applied to the **base and all 14 split manifests** as equal-length string-pool
replacements in the binary `AndroidManifest.xml` (AXML). This section records
the original `com.realme.mods` step; the current `com.realme.devanced` rename
uses the pool-resizing variant in §3.25:

- `package="com.realme.link"` → `package="com.realme.mods"`
- every authority/provision string equal to `com.realme.link`, or
  `com.realme.link.<suffix>` where suffix is one of the app's 30 provider
  authorities, gets the `com.realme.mods` prefix
- `com.realme.link.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` → same prefix swap
- external authorities that would collide with the official app:
  - `com.oplus.statistics.provider` (29) →
    `com.realme.mods.oplus.stats.s` (29)
  - `com.facebook.app.FacebookContentProvider589687081742846` (55) →
    `com.realme.modss.FacebookContentProvider589687081742846` (55)

Authority suffixes used:

```
AcAccountBaseSdkInitProvider
account.ams.AcAccountSDKInitProvider
account.open.sdk.AcOpenDBContentProvider
androidx-startup
bg_process
EventContentProvider
FacebookInitProvider
fileprovider
firebaseinitprovider
hybrid.imageCrop
MicrosoftCognitiveServicesSpeech.InternalContentProvider
MyDeviceAppProvider
mydevice.shortcuts
MyProcessProvider
oplus.epona
provider
share_preference
telecomprovider
Track.BalanceEventProvider
Track.MultiProcessSharedPreferencesProvider
Track.TrackCommonProvider
Track.TrackEventProvider
TuyaMonitorProcessInitContentProvider
TuyaOptimusProvider
ty_share.provider
UCAccountSDKInitProvider
verify_sys_fileprovider
WebPro.fileProvider
```

Do **not** replace component class names (`android:name="com.realme.link.*"`)
or action strings (`android:name="com.realme.link.ota.ACTION_*"`); they must
remain unchanged.

### 3.11 Skip the Quick guide — `HeadsetDeviceActivity`

File: `smali_classes40/.../headset/activity/device/HeadsetDeviceActivity.smali`

`onCreate` shows a first-run guide by navigating ARouter route
`/link/WEAR_GUIDE`, then finishes. The branch after the guide preference read:

```smali
move-result v1
goto :cond_8                      # was: if-nez v1, :cond_8
# guide: SPUtils.A(...); ARouter ... "/link/WEAR_GUIDE" ...; finish()
:cond_8
# continue in HeadsetDeviceActivity (device page)
```

Verified: opening the earbud/device card or tapping a detected earbud goes
straight to `HeadsetDeviceActivity`; `WearGuideActivity` is no longer started.

### 3.12 Remove the home notification bell — `MainFragment`

File: `smali_classes38/com/realme/link/home/MainFragment.smali`

Hidden IDs:

| View | ID |
|---|---|
| notification bell (`iv_message`) | `0x7f0a0584` |
| red dot (`dot`) | `0x7f0a0304` |

Inject at the **start of `onResume` and `onVisible`**, immediately after the
`invoke-super` call:

```smali
invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_hide_all
const v1, 0x7f0a0584
invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
move-result-object v2
if-eqz v2, :cond_hide_dot
const/16 v1, 0x8
invoke-virtual {v2, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_dot
const v1, 0x7f0a0304
invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
move-result-object v2
if-eqz v2, :cond_hide_done
const/16 v1, 0x8
invoke-virtual {v2, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_done
:cond_hide_all
```

Do **not** put this in `initViews`: at that point the header is not reliably
attached (`getView()` can be null or lack the header), and the bell stayed
visible. `onResume`/`onVisible` both work.

### 3.13 Replace the Me tab with a Settings button

File: `smali_classes38/com/realme/link/home/MainActivity.smali`

**a) Click handler** `E9(MainActivity, View)` — originally switched to
`UserFragment`. Replace the whole body:

```smali
.method public static synthetic E9(Lcom/realme/link/home/MainActivity;Landroid/view/View;)V
    .locals 2
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/realme/link/settings/syssettings/view/SysSettingsActivity;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    return-void
.end method
```

**b) Tab label/icon** — at the end of `onCreate`, after the Health/Workout
hide block:

```smali
const v0, 0x7f0a0ae3                 # tab_user_text
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_set_tab_label
check-cast v0, Landroid/widget/TextView;
const-string v1, "Settings"
invoke-virtual {v0, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
:cond_set_tab_label
const v0, 0x7f0a0ae1                 # tab_user_img
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_set_tab_icon
check-cast v0, Lcom/airbnb/lottie/LottieAnimationView;
const v1, 0x7f080913                 # drawable/setting_img
invoke-virtual {v0, v1}, Lcom/airbnb/lottie/LottieAnimationView;->setImageResource(I)V
:cond_set_tab_icon
```

**Do not call `LottieAnimationView.cancelAnimation()`** — this Lottie build
does not define it; the call throws `NoSuchMethodError` and crashes
`MainActivity.onCreate`. `setImageResource` (inherited from ImageView) is safe
and replaces the Me icon with the gear drawable.

Verified: bottom tab shows a gear + “Settings”; tapping it opens
`SysSettingsActivity` directly.

**c) Icon appearance: black gear only, no circle** — `res/drawable/setting_img.xml`
is a compiled vector drawable with two paths:

1. a filled circle `#ff78da59` (green), then
2. the gear `#ffffffff` (white).

Desired end state: drop the circle, keep only the gear in black. Both are
4-byte `TYPE_INT_COLOR_RGB8` (`0x1d`) typed values in the compiled AXML, so it
is an equal-length byte patch inside the APK (no resource rebuild):

| value | offset | before | after |
|---|---|---|---|
| circle fill data | `0x6b4` | `59 DA 78 FF` | `00 00 00 00` |
| circle dataType | `0x6b3` | `0x1D` (RGB8) | `0x1C` (ARGB8) |
| gear fill data | `0x718` | `FF FF FF FF` | `00 00 00 FF` |
| gear dataType | `0x717` | `0x1D` | `0x1C` |

Changing the type to ARGB8 is what makes the zero-alpha circle actually
transparent; leaving `0x1D` (RGB8) can force it opaque.

Use `work/patcher/patch_setting_img.py <base.apk> <out.apk>`, then
`zipalign -p -f 4`, re-sign, and reinstall the split bundle
(`adb install-multiple -r` with the patched base plus the existing splits).
Verify:

```bash
aapt2 dump xmltree --file res/drawable/setting_img.xml base.apk | grep fillColor
# -> #00000000 and #ff000000
```

The bottom bar is white, so the result is a plain black gear with no round
background. The source XML in `apktool-out/res/drawable/setting_img.xml` was
updated to the same colours for future rebuilds.

### 3.14 Remove the “Touch and hold to customize your banner theme” overlay

File: `smali_classes38/com/realme/link/home/MainFragment.smali`

`MainFragment.Jb()V` reads the `change_bg_guide` preference and, when false,
inflates `layout/main_bg_view_guide` through `GuideViewUtil` (hand icon,
dashed line, “Got it”). Disable it at the start of the method:

```smali
.method public final Jb()V
    .locals 3

    return-void

    # original body (unreachable): pref read + GuideViewUtil inflation
    ...
.end method
```

This removes the first-run banner-customization overlay without touching the
layout or the `change_bg_guide` preference.

### 3.15 Remove the device “Statement of Use” (GdprActivity)

File: `smali_classes38/com/realme/link/devices/gdpr/GdprActivity.smali`

When a device is connected for the first time, `GdprActivity.h9()` builds a
`GDPRDialog` (“Statement of Use”). The Agree handler `i9(...)` is what actually
proceeds: it stores the per-MAC consent, records authorization `"1"`, calls
`DeviceManager.V(context, device)`, and finishes the activity after 500 ms via
`j4/c`.

Patch `h9()` to run the Agree path directly, without creating/showing the
dialog:

```smali
.method private final h9()V
    .locals 3
    invoke-virtual {p0}, ...GdprActivity;->f9()Device;
    move-result-object v0
    invoke-virtual {v0}, ...Device;->getMac()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, ...GdprUtils;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    sget-object v1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    invoke-static {v0, v1}, ...SPUtils;->A(Ljava/lang/String;Ljava/lang/Object;)V
    invoke-virtual {p0}, ...GdprActivity;->f9()Device;
    move-result-object v0
    const-string v1, "1"
    invoke-static {v0, v1}, ...AuthorizeManager;->n(Device;String)V
    invoke-virtual {p0}, ...GdprActivity;->f9()Device;
    move-result-object v0
    invoke-static {p0, v0}, ...DeviceManager;->V(Context;Device)Z
    new-instance v0, Lj4/c;
    invoke-direct {v0, p0}, Lj4/c;-><init>(GdprActivity;)V
    const/16 v1, 0x1f4
    invoke-static {v0, v1}, ...ThreadUtil;->i(Runnable;I)V
    return-void
    # original dialog-building body left unreachable
.end method
```

This removes the per-device statement while keeping the normal connection
flow. The **app-launch** Statement of Use (shown by `SplashActivity` before the
region screen) is a separate consent and is not affected by this patch.

### 3.16 Remove the home weather/location section

File: `smali_classes38/com/realme/link/home/MainFragment.smali`

The block under “My devices” showing temperature/city — or “Failed to obtain
location” — is `ll_top_weather` (`0x7f0a06da`, binding field
`LayoutHomeHeaderBinding.q`). `MainFragment.Ya()` shows it when
`MainPresenter.m2()` is true and hides it otherwise. Force it hidden:

```smali
iget-object v1, v1, Lcom/realme/databinding/LayoutHomeHeaderBinding;->q:Landroid/widget/LinearLayout;
goto :cond_0                      # was: if-eqz v0, :cond_0
const/4 v0, 0x0                   # unreachable show path
:cond_0
const/16 v0, 0x8
:goto_0
invoke-virtual {v1, v0}, Landroid/view/View;->setVisibility(I)V
```

This removes both the weather row and the location-failure row.

### 3.17 Remove the manual device-type section on “Add device”

File: `smali_classes38/com/realme/link/devices/scan/SearchDeviceActivity.smali`

After the nearby-scan results, the page shows a manual section: the category
rail (`Band`, `Watch`, `Audio`, `Lighting`, `Security`, `Health`,
`Power-saving`, `Appliances`) plus the per-category device list. The whole
block is the included container `lha` (`0x7f0a063f`, binding
`LayoutHandAddBinding`).

Hide it in `onCreate` (alongside the virtual-device banner hide):

```smali
const v0, 0x7f0a063f                 # lha
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v0
if-eqz v0, :cond_hide_manual
const/16 v1, 0x8
invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
:cond_hide_manual
```

Verified: the Add device page shows only the nearby-device scan results; the
Band/Watch (and other manual categories) section is gone.

### 3.18 Earbud shows “Not used” / “Device not connected”

This had three layers; all are needed.

**a) Missing HEADSET device-manager route**

`BluetoothDMFactory.b(DeviceType)` resolves HEADSET through ARouter route
`/headset_DM/deviceManager`. That root is not loaded in the `:link.monitor`
process (no provider), so navigation returns null and the binder pool hands a
null binder to `BaseDeviceManagerProxy` (`Parcel: Expecting binder but got
null!`). The `HeadsetManagerDelegate` class itself is present in the base.

Patch `smali_classes38/com/realme/link/devices/BluetoothDMFactory.smali`:
when navigation returns null and the type is `DeviceType.HEADSET`, construct
the delegate directly:

```smali
move-object v1, p0                 # save DeviceType
...
invoke-virtual {p0}, Postcard;->navigation()Ljava/lang/Object;
move-result-object p0
check-cast p0, Lcom/realme/iot/common/BaseDeviceManager;
if-nez p0, :cond_1
sget-object v0, Lcom/realme/iot/common/devices/DeviceType;->HEADSET:Lcom/realme/iot/common/devices/DeviceType;
if-ne v1, v0, :cond_1
new-instance p0, Lcom/realme/iot/headset/manager/HeadsetManagerDelegate;
invoke-direct {p0}, ...-><init>()V
:cond_1
return-object p0
```

**b) Delegate `HeadsetBluetoothAdmin` never initialized**

The constructed delegate’s `f5()` NPEs on
`HeadsetBluetoothAdmin.m(String)` because its `l` field is set only by
`HeadsetManagerDelegate.setContext(Context)`. Call it right after creating the
fallback delegate:

```smali
invoke-static {}, Lcom/realme/iot/common/LibApp;->c()Landroid/content/Context;
move-result-object v0
invoke-virtual {p0, v0}, ...HeadsetManagerDelegate;->setContext(Landroid/content/Context;)Lcom/realme/iot/common/BaseDeviceManager;
move-result-object v0
```

Note: this smali assembler rejects `pop`; discard the result with
`move-result-object`.

**c) Login gates in the connect path**

Bypass the not-logged-in returns (change `if-nez` after
`AccountManagerWrapper.b()` to `goto`):

- `IotHeadsetManager.P(Device)` — “registerDisConnectDevice not login”
- `HeadsetManagerDelegate.connectDevice(Device, IConnectCallback)` — returns false
- `HeadsetManagerDelegate` `toConnect` path (~line 5852) — returns on not login
- `TLHeadsetConnectionImp.connectDevice(...)` — returns false
- `IotHeadsetController.onFunctions(...)` — returns null

Verified: the earbud page shows **40% battery**, Noise cancellation (Max),
Smart de-wind, EQ (`Clear Bass`), Spatial Audio; TL SDK handler messages flow;
tapping controls no longer shows “Device not connected”.

### 3.19 Home card still shows “Not used”

File: `smali_classes38/com/realme/link/home/MainPresenter.smali`

The home device card decides its status from `MainPresenter.z2(DeviceDomain)`,
which returned `this.l.contains(deviceDomain)` — a cached list of connected
domains that is only updated by the normal (logged-in) device event flow. The
device page was connected via the TL manager, but the cache stayed empty, so
the card rendered the “Not used” fallback
(`realme_common_status_unused`).

Patch `z2` to query the real device manager instead of the cache:

```smali
.method public z2(Lcom/realme/iot/common/domain/DeviceDomain;)Z
    .locals 3
    invoke-static {p1}, Lcom/realme/link/util/Util;->e(Lcom/realme/iot/common/domain/DeviceDomain;)Lcom/realme/iot/common/devices/Device;
    move-result-object v0
    const/4 v1, 0x0
    if-nez v0, :cond_0
    return v1
    :cond_0
    invoke-static {}, Lcom/realme/iot/common/LibApp;->c()Landroid/content/Context;
    move-result-object v2
    invoke-static {v2, v0}, Lcom/realme/link/devices/DeviceManager;->E(Landroid/content/Context;Lcom/realme/iot/common/devices/Device;)Lcom/realme/link/devices/IProductDeviceManager;
    move-result-object v2
    if-nez v2, :cond_1
    return v1
    :cond_1
    invoke-interface {v2, v0}, Lcom/realme/link/devices/IProductDeviceManager;->isDeviceConnected(Lcom/realme/iot/common/devices/Device;)Z
    move-result v0
    return v0
.end method
```

Verified: the home card now shows **40% battery** and the live noise controls
instead of “Not used”.

### 3.20 Device page opens as a floating dialog — make it full-screen

File: `smali_classes40/.../headset/activity/device/HeadsetDeviceActivity.smali`

The activity window was requested as `810x1800`, `fmt=TRANSLUCENT` with
`FLAG_DIM_BEHIND`, so it rendered as a rounded card with spacing around it.
Force a full-screen window right after `super.onCreate`:

```smali
invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
move-result-object v0
const/4 v1, -0x1                     # MATCH_PARENT
invoke-virtual {v0, v1, v1}, Landroid/view/Window;->setLayout(II)V
const/16 v1, 0x2                     # FLAG_DIM_BEHIND
invoke-virtual {v0, v1}, Landroid/view/Window;->clearFlags(I)V
const/4 v1, 0x0
invoke-virtual {v0, v1}, Landroid/view/Window;->setDimAmount(F)V
```

Verified: `dumpsys window` reports `mAttrs=(0,0)(fillxfill)` with no
`DIM_BEHIND`; the device page fills the display with no surrounding spacing.

### 3.21 All other pages still float — disable phone-mode window globally

File: `smali_classes37/com/realme/iot/common/utils/phonemode/PhoneModeKt.smali`

The per-activity fix above only covered the main device page. The shared cause
for `DeviceDetailActivity` (3-dot → Device details), Help, Open source
licenses and other pages was `BaseAbstractActivity` calling
`PhoneModeKt.c(activity)` / `PhoneModeKt.d(activity)`. `d()` resizes the window
to `a()x b()` (810x1800), sets `dimAmount = 0.8`, adds `FLAG_DIM_BEHIND`, and
applies the rounded `transparent_radius` background — the floating card look.

Disable it at the source:

```smali
.method public static final d(Landroid/app/Activity;)V
    .locals 5
    ...
    return-void                 # inserted before the original body
    const-string v0, "activity"  # original body left unreachable
    ...
.end method
```

`d()` has exactly one caller (`BaseAbstractActivity`), so this makes every
activity that used phone mode render as a normal full-screen page. Verified:
`DeviceDetailActivity` window is `(fillxfill)`, fmt `TRANSPARENT`, no
`DIM_BEHIND`, and the page fills the screen.

### 3.22 Empty gap left where the location row was removed

File: `smali_classes38/com/realme/link/home/MainFragment.smali`

After §3.16, the home header kept a large blank band between “1 device” and the
device card. The header content ends at `layout_home_header` (`y = 220`), but
the wrapping `cl_header_container` (`0x7f0a01ed`) measured `423` px because of
`android:minHeight="@dimen/sw_dp_188"` in `res/layout/fragment_main.xml`.

**Critical gotcha:** this app's ConstraintLayout keeps its **own** `mMinHeight`
field, distinct from `View.mMinHeight`:

- `View.setMinimumHeight(0)` does **not** clear it — the gap stayed at 188 dp
  even though `getMinimumHeight()` reported `0`.
- The XML `android:minHeight` is read into
  `ConstraintLayout.mMinHeight`; the correct setter is
  `ConstraintLayout.setMinHeight(I)V`.

Patch both lifecycle paths (same place as the bell/dot hides):

```smali
const v1, 0x7f0a01ed              # cl_header_container
invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
move-result-object v2
if-eqz v2, :cond_hide_hdr_r
check-cast v2, Landroidx/constraintlayout/widget/ConstraintLayout;
const/4 v1, 0x0
invoke-virtual {v2, v1}, Landroidx/constraintlayout/widget/ConstraintLayout;->setMinHeight(I)V
:cond_hide_hdr_r
```

Apply it in both `onResume()` (`.locals 5`) and `onVisible()` (`.locals 3`).
With the minimum cleared the container wraps its content: bounds become
`[0,0][1080,220]` and the device card moves up to `y = 220`.

To leave a breathing gap instead of zero, add a top margin to the device list
(`recyclerView`, `0x7f0a08fe`, first child of the same `LinearLayout`). Use a
**dp resource**, not a raw pixel value, so the gap scales with screen density:

```smali
const v1, 0x7f0a08fe
invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
move-result-object v2
if-eqz v2, :cond_gap_r
invoke-virtual {v2}, Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup$LayoutParams;
move-result-object v1
check-cast v1, Landroid/view/ViewGroup$MarginLayoutParams;
invoke-virtual {v2}, Landroid/view/View;->getResources()Landroid/content/res/Resources;
move-result-object v3
const v4, 0x7f070f68          # dimen/sw_dp_9 (9 dp)
invoke-virtual {v3, v4}, Landroid/content/res/Resources;->getDimensionPixelSize(I)I
move-result v3
iput v3, v1, Landroid/view/ViewGroup$MarginLayoutParams;->topMargin:I
invoke-virtual {v2, v1}, Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V
:cond_gap_r
```

`onVisible()` needs `.locals 5` (`v3`, `v4`). On the test device (density 2.25)
9 dp resolves to 20 px: `recyclerView` starts at `y = 240` (220 + 20) and the
device card at `y = 240`. On another density the same 9 dp is used.

Verification: `adb shell uiautomator dump` and compare
`cl_header_container` / `recyclerView` bounds before and after.

### 3.23 Rename the app to “Realme Link DeVanced”

The launcher label is `@string/link_app_name` = `0x7f130af0`, referenced by
`<application android:label>` and the launcher activity. After the clone rename
its default value is `realme Mods`, stored **once** in the *global* string pool
of `resources.arsc` (all locales point at the same entry).

`patch_arsc.py` only supports equal-length replacement, so a longer name needs
the pool resized. `work/patcher/patch_arsc_rename.py` does this without a
resource rebuild:

1. Parse the `ResTable` header and the global `ResStringPool` chunk.
2. Decode the target string. **UTF-8 pool gotcha:** every string has *two*
   length prefixes — the UTF-16 length (code units) followed by the UTF-8
   length (bytes). Decoding only one prefix produces garbage and hides every
   match.
3. Replace the encoded entry (`len16, len8, bytes, NUL`) and shift every
   later string offset by the byte delta.
4. Extend the string-data region with padding so the styles block stays
   4-byte aligned; update `stylesStart`, the pool chunk size, and the
   `ResTable` total size. Everything after the pool shifts unchanged.
5. Rewrite the `resources.arsc` zip entry, `zipalign`, re-sign.

Usage:

```bash
python3 -I work/patcher/patch_arsc_rename.py \
    in.apk out.apk "realme Mods" "Realme Link DeVanced"
```

The label resource now resolves to `Realme Link DeVanced` in every locale.
Verify before installing:

```bash
aapt2 dump badging out.apk | grep application-label:
# application-label:'Realme Link DeVanced'
```

The launcher shows the new name; the official `realme Link` stays separate.

### 3.25 Rename the clone package to `com.realme.devanced`

`com.realme.devanced` (19 chars) is longer than `com.realme.link` (15), so the
equal-length byte patch of §3.10 cannot be used. The clone package is only
stored in the binary `AndroidManifest.xml` of the base and splits (the
`resources.arsc` global pool contains no package strings — verified 0 matches).

`work/patcher/patch_axml_rename.py` rebuilds the manifest string pool instead:

1. Decode the pool (AXML pools are usually **UTF-16**: one length prefix in
   code units, then data, then `0x0000`; UTF-8 pools use the two-prefix form
   from §3.23).
2. Rewrite every string equal to or starting with `com.realme.mods` to
   `com.realme.devanced…` (package attribute, 30 provider authorities,
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). Class names and broadcast
   actions are untouched.
3. Recalculate all string offsets, `stringsStart`, `stylesStart`, the pool
   chunk size and the XML file header size; the rest of the AXML shifts
   unchanged.

Apply to the base **and every split**, then re-sign all 15 with one key:

```bash
python3 -I work/patcher/patch_axml_rename.py base.apk base.dev.apk \
    com.realme.mods com.realme.devanced
for f in split_*.apk; do
  python3 -I work/patcher/patch_axml_rename.py "$f" "out/$f" \
      com.realme.mods com.realme.devanced
done
# zipalign + apksigner each output (same key as before)
adb install-multiple -r --no-incremental out/base.dev.apk out/split_*.apk
```

Verified:

```bash
aapt2 dump badging out/base.dev.apk | head -1
# package: name='com.realme.devanced' versionCode='530214' ...
aapt2 dump xmltree out/base.dev.apk --file AndroidManifest.xml | grep authorities
# com.realme.devanced.fileprovider, com.realme.devanced.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, ...
```

The new package is a fresh Android identity: app data starts empty, the
first-launch Statement of Use appears again, and the old
`com.realme.mods` package (if still installed) remains side by side until
uninstalled. The system BLE bond survives, so the device is re-detected
without re-pairing. After install run
`cmd package compile -m speed -f com.realme.devanced` to restore cold-start
speed.

### 3.24 Remove rows from Settings and About

**Settings** — `smali_classes38/com/realme/link/settings/syssettings/view/
SysSettingsActivity.smali`, layout `res/layout/activity_sys_settgins.xml`.
Hide at the start of `onResume()` (after `invoke-super`):

| row id | label | id value |
|---|---|---|
| `ilNotify` | Show connection status in notification drawer | `0x7f0a0490` |
| `ilKeepAliveApp` | App protection | `0x7f0a048f` |
| `ilThirdApp` | Sync data to a third-party service | `0x7f0a0492` |
| `il_account_security` | Account management | `0x7f0a0493` |
| `item_person_info_list` | Personal Information We Collect | `0x7f0a04e9` |
| `item_third_party_info_share` | Personal Information We Share with Third Parties | `0x7f0a04f2` |

```smali
const v0, 0x7f0a0490
invoke-virtual {p0, v0}, Landroid/app/Activity;->findViewById(I)Landroid/view/View;
move-result-object v1
if-eqz v1, :cond_rm_notify
const/16 v0, 0x8
invoke-virtual {v1, v0}, Landroid/view/View;->setVisibility(I)V
:cond_rm_notify
# ... repeat per id
```

`GONE` (not `INVISIBLE`) is required: the rows are direct `LinearLayout`
children, so they collapse instead of leaving blank bands.

**About** — `smali_classes38/com/realme/link/settings/about/
AboutLinkActivity.smali`, layout `res/layout/activity_about_link.xml`. Add at
the end of `initViews()`:

| row id | label | id value |
|---|---|---|
| `item_privacy` | Privacy Notice | `0x7f0a04ea` |
| `item_service_agreement` | Service Agreement | `0x7f0a04ef` |
| `item_open_source_license` | Open source licenses | `0x7f0a04e8` |
| `item_user_experience` | User Experience Program (includes the experience notice text) | `0x7f0a04f6` |
| `view` | divider below the removed rows | `0x7f0a0e1f` |
| `item_protect_info` | Withdraw consent to Privacy Notice | `0x7f0a04ec` |

The About rows are a `ConstraintLayout` chain. When only the four legal rows
were hidden, the leftover divider and “Withdraw consent” row collapsed up and
overlapped the icon/version (the chain had no visible content left below it).
Hiding those two as well leaves a clean page: icon + version only. Because
everything below the version text is `GONE`, the chain collapses harmlessly
and no runtime constraint rewrite is needed.

If the “Withdraw consent” row must be kept instead, re-anchor the divider under
the version text (`ConstraintLayout$LayoutParams.topToBottom = tv_version
0x7f0a0d84`, `topToTop = -1`); both fields are public and can be changed at
runtime without a resource rebuild.

---

## 4. Build pipeline used

1. Decode original base, keeping dex files as files (no smali unless needed):
   ```bash
   apktool d -s -f -o apktool-out /tmp/pulled-realme-link/base.apk
   ```
2. Apply all smali patches to `apktool-out/smali*/`.
3. Rebuild dex:
   ```bash
   apktool b apktool-out -o /tmp/base-mod.apk
   ```
   The resulting APK's **resources are NOT used** (aapt2 dropped the
   `0x80`–`0x84` packages), only `classes36.dex`, `classes38.dex`,
   `classes40.dex`.
4. Start from the **original base APK** (keeps resources.arsc with all
   resource packages) and swap the three patched dex files:
   `work/patcher/swap_dex.py`.
5. Patch `resources.arsc` strings (label/count) — same-length bytes:
   `work/patcher/patch_arsc.py`.
6. Patch binary `AndroidManifest.xml` in base + all splits:
   `work/patcher/patch_axml.py`.
7. Align and sign every APK with the same key:
   ```bash
   zipalign -p -f 4 in.apk out.apk
   apksigner sign --ks ~/.local/opt/rea-mod.keystore --ks-key-alias rea-mod \
       --out signed.apk out.apk
   ```
   v1+v2 signatures are produced; all 15 APKs must share the same cert.
8. Install:
   ```bash
   adb install-multiple -r signed/base.apk signed/split_*.apk
   adb shell cmd package compile -m speed -f com.realme.devanced
   ```

---

## 5. Verification checklist

- [ ] App launches straight to `MainActivity` (“My devices”), no login screen.
- [ ] Bottom bar shows only Home and Me.
- [ ] Home has no Virtual experience strip, no “N virtual devices” subtitle,
      no Smart AI card, no Health trends card.
- [ ] Me page shows only Register/Sign in + Settings.
- [ ] Pair device opens `SearchDeviceActivity`; scanning finds devices.
- [ ] Scanner has no “Virtual devices available” banner.
- [ ] Tapping a detected earbud or the device card opens the device page
      (Noise control / EQ / Spatial Audio) **directly** — no Quick guide, no
      task restart, no `Resources$NotFoundException`.
- [ ] Home header has no notification bell (`iv_message`) and no red dot.
- [ ] Bottom-right tab reads **Settings** with a black gear icon; tapping it
      opens `SysSettingsActivity` directly.
- [ ] First home open shows no “Touch and hold to customize your banner
      theme.” overlay.
- [ ] First device connection shows no per-device “Statement of Use”;
      the device page (Noise control / EQ / Spatial Audio) opens.
- [ ] No “How do you like…” rating overlay.
- [ ] `adb shell pidof com.realme.devanced` stays stable during the above.
- [ ] `adb logcat -b crash` has no new entries for `com.realme.devanced`.

---

## 6. Designing the future on-device patcher APK

### 6.1 What the patcher must do

1. Copy the target split bundle (base + splits) into app-private storage.
2. Patch three dex files (semantic or byte-level).
3. Patch binary AXML in base + splits.
4. Patch `resources.arsc` strings (optional; label/count).
5. Zip/align and re-sign all APKs with one generated key.
6. Install the split set via a `PackageInstaller` session with
   `setSplitApkFiles`/`write` (equivalent of `install-multiple`), after the
   user uninstalls the official app.

### 6.2 Recommended patching technique per change

| Change | Technique |
|---|---|
| `return-void` method stubs (login helpers, RateUtil.e) | dexlib2: replace `MethodImplementation` with an empty return; or byte-patch the first instruction to `return-void` only if no debug/verification issue (safer with a dex library) |
| Branch inversion/removal (`if-eqz` → `goto`, login gates) | dexlib2 instruction rewrite; or NOP the branch opcode if a NOP is acceptable |
| Constant changes (`const/4 0x0` → `const/16 0x8`) | locate the enclosing method via dexlib2 and rewrite the literal; never blind-patch bytes across all dex |
| Inject method calls (addAssetPath, findViewById hides) | dexlib2: append instructions to an existing method, or add a small helper class and call it from `LinkApplication.onCreate` / lifecycle methods |
| Package/authority rename | equal-length string replacement in the binary AXML string pool (no rebuild) |
| Resource strings | equal-length byte replacement in `resources.arsc` (no rebuild) |
| Re-sign | Android `apksig` library (v1+v2+v3) with a generated key; sign base and every split with the same key |
| Alignment | use `zipalign` equivalent or keep uncompressed entries aligned; `extractNativeLibs=false` requires 4 KiB-aligned `.so` |

Do not attempt to recompile resources with aapt2 on-device: it drops the
shared resource packages (`0x80`+). Keep the original `resources.arsc`.

### 6.3 Package rename constraints

- Equal byte length (`com.realme.link` → `com.realme.mods`, both 15) keeps AXML
  string-pool offsets stable and allows a trivial byte patch. A longer name
  (`com.realme.devanced`, 19) requires the pool resize in §3.25.
- Rename all provider authorities and the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  on base + every split. Two apps declaring the same provider authority cannot
  coexist.
- Rename the two external authorities listed in §3.10 with equal-length values.
- Do not rewrite Java class names or broadcast action strings.

### 6.4 Split bundle constraints

- All 15 APKs must be signed with one cert; otherwise install-multiple fails.
- Re-signing forces uninstalling the official app; `allowBackup=false` means
  local data is lost. The patcher must warn the user.
- The `realmeHeadset` dynamic module is not present in the pulled bundle. The
  clone works because:
  - `addAssetPath` loads the `0x80` resources from the base APK, and
  - the two headset activities' login checks are bypassed so they don't call
    `DeviceUtils.w()` and restart the task.
- Play Core feature-split state is preserved by keeping a real split bundle
  (do not merge into one APK; merging also breaks dynamic resource packages).

### 6.5 Login / account limits

The clone is intentionally account-free. Consequences:

- Cloud device sync/binding, messages, health sync/mall calls cannot
  authenticate.
- Local BLE pages and device controls open; earbud page was verified.
- Pair-device and headset flows no longer restart; cloud-dependent steps may
  fail silently.

### 6.6 Verification inside the patcher

- Compare before/after SHA-256 of every patched entry.
- Decode the manifest after patching with `aapt2 dump badging` (or an AXML
  parser) and assert the package/authority set.
- Assert all split APKs have the same signer cert.
- After install, launch `MainActivity` and check logcat for
  `Resources$NotFoundException`, `NoSuchMethodError`, `FATAL EXCEPTION`, and
  `CLEAR_TASK` restarts.

---

## 7. Lessons / pitfalls

1. **apktool rebuild drops shared resource packages.** The base contains
   packages `com.realme.link.realmeHeadset` (`0x80`) through
   `vendorRyeexWatch` (`0x84`). `apktool b` keeps only `0x7f`. Therefore:
   patch dex with apktool, but assemble the final APK from the original base
   and swap dex files; patch manifest/resources by bytes.
2. **APKEditor merge preserves the packages but loses dynamic module state.**
   A merged APK opened `HeadsetDeviceActivity` and crashed on
   `0x80070007` because the module asset path was never registered. Do not use
   the merged approach.
3. **`Fragment.findViewById` does not exist in this app's AndroidX version.**
   It caused `NoSuchMethodError` and a real crash on the Me tab. Use
   `Fragment.getView().findViewById`.
4. **“Crash on device click” was a restart, not a crash.**
   `DeviceUtils.w(Context)` sends `MAIN + CLEAR_TASK`; logcat is required to see
   it. Check `ActivityTaskManager` START/flags and `exit-info`, not only
   `FATAL EXCEPTION`.
5. **Resource package IDs:** `0x7f` = base app; `0x80+` = shared/dynamic
   resource packages. They need `addAssetPath` (or split installation) to be
   resolvable.
6. **`resources.arsc` string replacement is same-length only.** Changing
   length requires rebuilding the pool/table and risks offsets.
7. **AOT compile after install** (`cmd package compile -m speed`) reduces cold
   start from ~4.5 s to ~1.5 s on the test device.
8. **Do not include the keystore password** in the patcher or this document.
9. **Lottie API surface is reduced.** `LottieAnimationView.cancelAnimation()`
   does not exist in this build (crashed with `NoSuchMethodError`).
   `setImageResource()` (inherited from ImageView) works for swapping the tab
   icon.
10. **Hide header views in `onResume`/`onVisible`, not `initViews`.** At
    `initViews` time the included `layout_home_header` is not reliably attached,
    so `getView().findViewById()` can miss the bell/dot.

---

## 8. Script inventory

- `work/patcher/patch_axml.py` — equal-length string-pool patcher for
  `AndroidManifest.xml` (base + splits). Takes an APK and a mapping.
- `work/patcher/patch_arsc.py` — equal-length string patcher for
  `resources.arsc`.
- `work/patcher/swap_dex.py` — replace `classes*.dex` entries in the original
  base APK with patched ones.
- `work/patcher/patch_axml_rename.py` — variable-length package-prefix rename:
  rebuilds the manifest string pool and all offsets (§3.25).
- `work/patcher/patch_setting_img.py` — transparent-circle / black-gear patch
  for `res/drawable/setting_img.xml` (§3.13c).
- `work/patcher/patch_arsc_rename.py` — variable-length app-name patch that
  resizes the global string pool in `resources.arsc` (§3.23).
- `work/patcher/build_clone_split.py` — original end-to-end build harness
  (swaps dex, patches manifest, aligns, signs).
- `work/patcher/patch_map.json` — authority/package mapping used by the
  scripts.

The exact smali patch sites are also preserved in
`work/base58/apktool-out/` and documented in §3.
