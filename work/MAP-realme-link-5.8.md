# realme Link 5.8.811.2814 — app-shell map

Analysis target: installed `com.realme.link` split bundle pulled read-only from the
connected Infinix X6831 (Android 13) on 2026-11-09.

## 1. Target identity

| Field | Value |
|---|---|
| Package | `com.realme.link` |
| Version | 5.8.811.2814 (versionCode 530214) |
| compile/target SDK | 36 / 36, minSdk 26 |
| Signer | `CN=ROM, O=realme, EMAILADDRESS=apps_link@realme.com` |
| SHA-256 cert | `eb8d65ac...27623bb7` |
| APK set | `base.apk` + 14 splits (ABI, xxhdpi, realmeBracelet, vendorIdoBand, vendorLifesenseWatch, vendorRyeexWatch, vendorTouchGWatch) |
| Processes | main, `:link.monitor`, background process |
| Backup | `allowBackup=false` (uninstall loses local data) |

The supplied monolithic APK `1.2.105.18` (versionCode 323) is an old build with the
same signer but a different architecture. All findings below are for 5.8.x.

## 2. Toolchain / analysis artifacts

- JDK 21, jadx 1.5.6, apktool 3.0.3 in `~/.local/opt`; wrappers in `~/.local/bin`
- Android SDK build-tools 35 (`aapt2`, `apksigner`, `zipalign`, `d8`) at `~/Android/Sdk`
- Pulled bundle: `/tmp/pulled-realme-link/`
- Decoded base: `work/base58/apktool-out/` (smali + resources + assets)
- Targeted Java: `work/base58/jadx-{0,35,38}/sources/` (shell dex only)
- Shell code is concentrated in `classes38.dex` (3,030 `com.realme.link` classes);
  `classes.dex` and `classes35.dex` hold the remainder

## 3. Application startup

`LinkApplication` → `LinkApplicationInitHelper` (main / background process init).

`SplashActivity` (exported, launcher) flow:

1. optional location read → country/region
2. permission gate (`PermissionConstant.n()` location)
3. first-run region selection → `InitRegionActivity` / `WelcomeActivity`
4. login check → `LoginActivity` (WebView-based account login)
5. `MainActivity`

Splash also fetches a server **startup ad** (`StartupPageBean`, via
`ConfigDataApi.getStartupPage(date, regionCode)`), preloads up to 5 images,
renders a clickable full-screen ad, and tracks `banner_click` with
`ad_location=open_screen`.

## 4. Main shell / tabs

`MainActivity` (`com.realme.link.home.MainActivity`) hosts server-configurable tabs:

| Tab | Implementation | Gate |
|---|---|---|
| Home / devices | `MainFragment` + `DeviceManager` | always |
| Health | `HealthFragment` | login/sport config |
| User / me | `UserFragment` | always |
| Mall / shop | `FeatureLoadFragment` (dynamic split) | `storeInfo.isMallTabEnabled() && !isTablet` |
| Explore / community | `DiscoverFragment`, `HomeSceneActivity` | `storeInfo.isCommunityTabEnabled()` |
| Sports | `FeatureLoadFragment` / `HealthFragment` paths | `TAB_SPORT` pref |

`FeatureLoadFragment` installs on-demand feature bundles through Play Core split
install (`FeatureInstallListener`, `SplitInstallManager`), so large features are
downloaded/installed per device. Tabs are also gated by server config cached in
`StoreInfo`, not compiled-in.

Event names visible in the shell: `JumpShop`, `JumpDiscover`,
`discover_coolplay_clicks`, plus lifecycle/profile events.

## 5. Hybrid / WebView layer

Three WebView stacks are present:

1. **Native shell H5** — `com.realme.link.webview.WebViewActivity`
   - `WebViewJavascriptBridge.js` (`assets/`, scheme `yy`)
   - JS interfaces: `"android"` → `com.realme.iot.common.webview.WebViewBridgeFace`,
     and `"goBack"` → `WebViewActivity$JsGoBack`
   - `WebViewBridgeFace` methods: `getToken`, `getOPToken`, `getCommonParams`,
     `getMark`/`setMark`, `getPhoneModels`, `login`, `showTitle`, `toFeedback`,
     `toSystemSetting`, `eventTracking`
2. **Feedback / device H5** — `BridgeWebViewActivity` using `com.github.lzyzsd.jsbridge`
   (`BridgeWebView`), interface `"android"`, `getToken()` + `toFeedback()`
3. **SDK-internal** — HeyTap `com.heytap.webpro` (450 files), Tuya `com.tuya.smart.jsbridge`,
   React Native WebView, plus Facebook/Twitter OAuth WebViews

Content links are loaded by URL; `WebViewActivity` has an `advertise` branch and
rewrites some links to localized hosts.

## 6. Telemetry / tracking

| Mechanism | Location | Notes |
|---|---|---|
| `StatisticsHelper` / `StatisticsInitWrapper` | `com.realme.iot.statistics`, `com.realme.link.feature.statistics` | homegrown event bus; `onEvent(group, event, params)`; groups `me`, `mall`, `main_container`, `home`, `health`, `explore`, `dev`, `sports` |
| `FirebaseManager` | `com.realme.iot.statistics` | concrete `IStatisticsApi`; Firebase Analytics events + Crashlytics |
| Crashlytics | base + `firebase` | crash collection toggle via config |
| Umeng | `com.realme.iot.common.umeng` | push registration / aliases (Tuya push) |
| Custom device statistics | `StatisticsTimerTaskHelper`, `DeviceManagerCmd.UPLOAD_STATISTICS` | device health/step/usage uploads |

Statistics are gated by `user_experience_switch_status` pref and by region
(`StatisticsInitWrapper.Companion.g(region)`); `StatisticsInitWrapper.e(boolean)`
and `StatisticsHelper.b(boolean)` switch them on/off.

## 7. Ads / commerce / promo surfaces

- Splash startup ad + `banner_click`/`open_screen` tracking
- Mall tab (server-enabled, dynamic feature)
- Explore/community tab, `HomeSceneActivity`, `discover_coolplay_clicks`
- `messagecenter` package (48 classes)
- WebView `advertise` page type
- Facebook/Twitter share/login SDKs (promo/social surfaces, 4,339 + 638 classes)

## 8. Sensitive permissions and the features behind them

| Permission | Feature |
|---|---|
| `READ_SMS` | SMS forwarding to watch/band via `PhoneNoticeHelper` + `SmsObserver` |
| `READ_CONTACTS` | contact-name resolution for incoming calls/SMS |
| `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `READ_CALL_LOG` | `IntelligentNotificationService` + `PhoneStateListener` call notifications |
| Location (incl. background) | BLE scan, region detection, GPS devices, geofence |
| `READ_MEDIA_*`, storage | watch faces (`DialDeployActivity`), avatars, firmware |
| Camera | watch-face capture, QR scan |
| BODY_SENSORS, ACTIVITY_RECOGNITION | health/wearable features |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, foreground services | BLE keep-alive, notification listener |

`IntelligentNotificationService` is exported with
`BIND_NOTIFICATION_LISTENER_SERVICE`; `RemoteControllerService` and
`UploadServerDataService` are exported connected-device foreground services.
`MyDeviceProvider` / `ShortcutsProvider` are exported with the OPlus IOT signature
permission.

## 9. Update path

`com.realme.link.updateApp.UpdateAppApi` → `api/v3/app/version/check/update` and
`UpdateAppUtil` (version compare, APK download, install prompt via
`REQUEST_INSTALL_PACKAGES`). A re-signed personal build can be replaced by an
official update unless this check is disabled or its prompt suppressed.

## 10. Signature / integrity checks

No application-wide self-signature check was found in `com.realme.link` or
`com.realme.iot`. Hits were unrelated (`com.amazon.identity.auth...PackageSignatureUtil`,
BouncyCastle PKIX). No Play Integrity / SafetyNet / App Check usage found.
This makes a re-signed build viable; it does not prove server-side or native
checks are absent.

## 11. Edit candidates (for the next step)

Removals:

- **Splash ads**: stop `StartupPageBean` fetch/render and `banner_click` tracking in
  `SplashActivity`/`SplashPresenter`; keep the normal splash.
- **Mall / community tabs**: force `isMallTabEnabled()` / `isCommunityTabEnabled()`
  false (or hide the tab views) in `MainActivity`.
- **Telemetry**: force `StatisticsHelper.b(false)` + set
  `user_experience_switch_status=false`; optionally gate Firebase/Crashlytics
  initialization. `StatisticsInitWrapper` is the single choke point.
- **Update nags**: short-circuit `UpdateAppUtil` version check.
- **Sensitive permissions**: remove SMS/contacts/call permissions and the
  `PhoneNoticeHelper` forwarding paths, or leave the service but drop the
  permission requests. This removes watch notification/call relay.
- **Social SDKs**: Facebook/Twitter login/share (large code, but also used for login).

Additions (need a concrete request): new `WebViewBridgeFace` JS method, a new
settings entry, a home-screen shortcut/tile, local export/logging, custom device
control, or a package-rename clone.

## 11a. Applied changes (2026-11-09)

Targets in `work/base58/apktool-out`:

- **Login removed** — `com.realme.iot.account.internal.RealAccountHelper` (classes36):
  - `C(Context)` now starts `com.realme.link.home.MainActivity`
    (flags `NEW_TASK|CLEAR_TOP`) instead of `LoginActivity`. This is the only
    place that constructed the login screen.
  - `c(Context)` (re-login fragment), `p(Context)` (re-login tip dialog) and
    `z(Activity)` (login dialog) are now no-ops.
- **Home cards hidden** — `MainFragment.initViews` (classes38):
  - `cl_health_trend` (`FragmentMainBinding.m`) now always `GONE`
  - `cl_ai` (`FragmentMainBinding.k`) now always `GONE`
- **Virtual experience hidden** — `MainFragment.F1` (classes38):
  - `rl_virtual_device_title` (`.D`), its title (`.K`) and
    `recycle_view_virtual_device` (`.A`) now stay `GONE` on both the
    non-empty and empty branches (`v2` is `0x8`; the earlier `v3` patch was
    inverted and fixed).
  - `res/values/plurals.xml` `link_home_virtual_device_number` items are now
    empty, removing the "N virtual devices" header subtitle. Only
    `MainFragment` used this plural.
- Splash ad, telemetry, permissions and other tabs were **not** changed.

First install (2026-11-09): official app uninstalled, modified 15-APK bundle
installed, launched directly into "My devices" with no login screen. The first
build still showed the virtual strip because of the inverted constants; the
corrected build (`build-base-mod2.apk`, installed as a same-key update) was
verified on the device:

- app launches to `MainActivity` ("My devices"), bottom tabs Home / Health /
  Workout / Me
- no login screen; tapping **Pair device** does not open one and does not crash
- no Virtual experience section and no "N virtual devices" subtitle
- no Smart AI card and no standalone Health trends card
- process stable, no `FATAL EXCEPTION` in logcat

Known consequence: with no account token, account-gated actions (pair device,
messages, health sync, mall) are inert. The account redirect only removes the
login UI; it does not mint a token.

## 11b. Coexisting clone (`com.realme.devanced`)

A second, independently installable build was produced so the official
`com.realme.link` can be installed alongside it.

- Identity: package `com.realme.devanced`, label **Realme Link DeVanced**, same
  version/code. (The first clone was `com.realme.mods` / “realme Mods”; the
  package was later renamed. History is kept in the patch recipe §3.25.)
- Structure: **base + all 14 original splits**, not a merged APK. Merging with
  APKEditor preserved resource packages but an apktool resource rebuild dropped
  them; the split bundle keeps `0x7f`–`0x84` packages and Play Core modules.
- Build method:
  - original base APK with `classes36.dex`/`classes38.dex` swapped from the
    patched apktool build (login/card/device-gate patches)
  - binary `AndroidManifest.xml` string-pool patch in base and all splits:
    `com.realme.link` → `com.realme.mods` (equal length originally, then
    `com.realme.mods` → `com.realme.devanced` with a resized pool), 30 provider
    authorities, the dynamic-receiver permission, plus the OPPO/Facebook
    provider authorities (unique values)
  - `resources.arsc` string patch: `realme Link` → `realme Mods` and the
    virtual-device plural blanked (same-length, no resource rebuild)
  - `zipalign -p 4`, then all 15 APKs signed with `rea-mod.keystore`
  - installed with `adb install-multiple`
- Pair device: the add-device login gate in `MainFragment` was removed, so the
  button opens `SearchDeviceActivity` without an account. Verified: scanner
  opens, realme Band row opens `SearchTypeDeviceActivity` (location permission
  required).
- Headset/earbud path: `HeadsetDeviceActivity` crashes on resource
  `0x80070007` because the `realmeHeadset` **dynamic feature module** is not in
  the pulled bundle. This is unrelated to the button patch; the official
  sideloaded bundle lacks the same module.
- Artifacts: `work/base58/signed-mod/` (old single build),
  `/tmp/clone-devanced-signed/` (current split clone). Patcher scripts:
  `work/patcher/`.

### 11c. Crash fix and UI removals (latest build)

- **Resource crash fix**: `LinkApplication.onCreate` now calls
  `com.google.android.play.core.splitcompat.zza.zzc(AssetManager, File)` with
  the base APK path, registering the embedded `com.realme.link.realmeHeadset`
  resource package (`0x80`) at runtime. Verified: `HeadsetDeviceActivity`
  starts with no `Resources$NotFoundException`.
- **Bottom bar**: `MainActivity.onCreate` hides `tab_health_all` (0x7f0a0ac9)
  and `tab_sport_all` (0x7f0a0ad7) via `findViewById`; the sport show site was
  also forced to GONE. Verified: only Home/Me remain.
- **Me page**: `UserFragment.onResume` and `onVisible` hide
  `fictitious_experience` (0x7f0a039c), `voice_service` (0x7f0a0e52),
  `ll_scene` (0x7f0a06c3), `me_target` (0x7f0a0729), `encourage` (0x7f0a032c)
  and `help` (0x7f0a045e) through `Fragment.getView().findViewById`.
  Verified on device: Me page now shows only "Register/Sign in" and
  "Settings"; all six row IDs are absent from the UI hierarchy.
- **Scanner banner**: `SearchDeviceActivity.onCreate` hides `rl_remind`
  (0x7f0a096c). Verified: banner absent, nearby device detection still works.
- **Earbud path**: tapping the detected "realme Buds Wireless 5 ANC" opens
  `HeadsetDeviceActivity` with no `Resources$NotFoundException`; the activity
  returns to home because the earbud is not actually connected. No app crash.
- Note: `Fragment.findViewById` does not exist in this app's AndroidX build;
  using it crashed `UserFragment.onResume` with `NoSuchMethodError`. The
  ID-based hide uses `getView()` instead.

### 11d. The "device click crash" was a self-restart

Live logcat showed no Java/native crash. Tapping a detected earbud started
`HeadsetDeviceActivity`, which then started `SplashActivity` with
`MAIN + CLEAR_TASK` (`0x30008000`), destroying the task and returning to Main.
That is `DeviceUtils.w(Context)`, called from the not-logged-in branch:

- `HeadsetDeviceActivity.onCreate`: `AccountManagerWrapper.b()` false →
  `DeviceUtils.w()` + `finish()`
- `HeadsetOneSpaceActivity.initData`: same pattern

Patch: replaced both `if-nez` login branches with `goto` so the pages continue
without an account. Verified: earbud → WearGuideActivity ("Quick guide") →
"Get started" → headset device page with Noise control / EQ / Spatial Audio;
no `CLEAR_TASK`, no `Resources$NotFoundException`, no process crash.

The earlier `addAssetPath` registration is still required: it makes the
`0x80` resource package resolvable once the activity is allowed to run.

### 11e. Rating / feedback prompt removed

The overlay "How do you like realme Link?" with "Give feedback" / "Love it"
comes from `com.realme.link.util.RateUtil.e(Activity, String)`, called from
`MainActivity.onStart` (`RateUtil.c`) and from main/health/user fragments.
Patch: `RateUtil.e` now returns immediately, disabling every rating/feedback
prompt path. Verified: no "How do you like", "Give feedback" or "Love it"
content in the UI hierarchy after relaunch.

Consequence: without an account token, account-backed features (cloud device
sync/binding, health, messages, mall) cannot complete. Local state still renders.
The login entry points now land on the home screen and gated taps do nothing.

Build result:

- `work/base58/build-base-mod.apk` (161 MB, 40 dex, rebuilt by apktool 3.0.3)
- `work/base58/signed-mod/` — modified base + all 14 original splits, all signed
  with `rea-mod.keystore` (`SHA-256 98719857...93c231`)
- Verified: 15/15 APKs share the same signer certificate

## 12. Build / install plan

1. Patch smali/resources in `work/base58/apktool-out/`.
2. `apktool b apktool-out -o base-mod.apk`.
3. Re-sign `base-mod.apk` **and every original split** with one personal key
   (`~/.local/opt/rea-mod.keystore`); signatures must match across the set.
4. `adb uninstall com.realme.link` (signature change; `allowBackup=false` means
   local data is lost), then
   `adb install-multiple base-mod.apk split_*.apk`.
5. Re-login; re-pair devices if pairing state is local.
6. Verify: launch, tab set, no startup ad, telemetry switch off, device flows.

Baseline validation (done 2026-11-09):

- `apktool b apktool-out` on the untouched base succeeded → `build-base-null.apk` (161 MB)
- Re-signed rebuilt base + all 14 original splits with `rea-mod.keystore`; all 15
  now report the same certificate
  `SHA-256 98719857b36d95767f7f319e42e8752e2e44a78dbe10b6907ff386724093c231`
- Outputs in `work/base58/signed-null/`
- No install performed; installing a re-signed build requires uninstalling the
  official app first (different signing key), which wipes local data.
