# Document 01 — APK Analysis (Reference: `مستكشف ملفات ES_4.4.2.2.1.apk`)

This document is a **reverse-engineering / specification source of truth** for the APK
that ships in this repository. It records *what the app is*, *how it is built*, and —
critically — *what its provenance problems are*, so the from-scratch rebuild (Doc 02) can
inherit the useful feature set while discarding the junk.

All facts below were extracted statically from the APK (manifest, `resources.arsc`, dex
string/type tables, native libs, assets). No code was executed.

---

## 1. Identity & build metadata

| Field | Value |
|---|---|
| Package | `com.estrongs.android.pop` |
| App name (label) | ES File Explorer (「ES」/ estrongs) |
| Version name | `4.4.2.2.1` |
| Version code | `15036` |
| `minSdkVersion` | **19** (Android 4.4 KitKat) |
| `targetSdkVersion` | **30** (Android 11) — *outdated* |
| `compileSdkVersion` | 23 (Android 6.0) — *very outdated* |
| Application class | `com.estrongs.android.pop.FexApplication` |
| `largeHeap` | `true` |
| `requestLegacyExternalStorage` | `true` (pre-Android-11 storage model) |
| Launcher activity | `com.estrongs.android.pop.app.openscreenad.NewSplashActivity` |
  (note the package name: the **entry point lives in the ad/splash module**) |

### Signing
| Field | Value |
|---|---|
| Subject | `CN=xiao, OU=estrongs, O=estrongs, L=Beijing, ST=Beijing, C=CN` |
| Valid | **2009-03-04 → 2063-12-06** (a 54-year cert, reused for years) |
| Signature algo | RSASSA-PKCS1-v1.5 (v1 / JAR signature) |
| SHA-256 fingerprint | `08:E7:CF:9D:16:6F:82:55:3F:C8:9A:44:7A:DA:FF:3B:F1:7A:B5:3E:A7:9B:97:43:C2:50:FC:DF:C5:7F:A7:5B` |

The certificate is the **long-lived original estrongs key**. However — see §3 — the
binary itself shows clear signs of having been repackaged after signing.

---

## 2. Scale & composition

| Metric | Value |
|---|---|
| APK size | ~36.9 MB |
| DEX files | 4 (`classes.dex` + `classes2/3/4.dex`) |
| Unique class references (from type/string pool) | **33,371** |
| Method IDs (declared in headers) | ~230,852 |
| Field IDs (declared in headers) | ~170,224 |
| Activities | **150** |
| Services | 16 |
| Receivers | 7 |
| Providers | 5 |
| Layouts (`res/layout*`) | **862** files |
| Resource entries (`res/`) | 4,955 |
| Native libraries (`lib/`) | 21 `.so` across 5 ABIs |

Top-level entry count: `res/` 4,955 · `assets/` 154 · `lib/` 21 · `META-INF/` 7.

---

## 3. ⚠️ Provenance — this is a repacked / modified build

This is the single most important finding for the rebuild decision. The APK is **not a
clean, official estrongs release**. Evidence:

1. **Corrupted DEX ID tables.** In every DEX the `class_defs`, `method_ids` and
   `field_ids` tables contain out-of-range / constant sentinel values
   (e.g. `name_idx` values like `0x3374E06`, repeated `0x22e7` / `0x11`), while the
   `string_ids` and `type_ids` tables are fully intact. A genuine, officially-built DEX
   has all tables consistent. This pattern is the signature of a **repack tool that
   rebuilt the string/type pools but destroyed the ID tables** — the code itself is no
   longer executable as signed.
2. **Ad-injected search redirects.** `assets/dxtoolbox/search_engines_property.json`
   routes its “Bing” and “Default” engines through third-party proxy domains
   (`api-client.mobitech-search.xyz`, `search.abclauncher.com`) instead of the real
   engines. This is an **ad/traffic-injection** mechanism, not something in a clean build.
3. **OEM ad-supplier mediation.** `assets/supplierconfig.json` holds per-OEM
   (`vivo`/`xiaomi`/`huawei`/`oppo`) ad `appid`s — an ad-monetization mediation layer.
4. **Ad/junk path list.** `assets/adjunk.txt` is a large JSON list of ad & junk cache
   paths (Umeng, Baidu, Tencent, Alipay, etc.) — data that belongs to an ad/cleaner
   ecosystem.
5. **Legacy `androidsupportmultidexversion.txt`** + a `trusted-certs.raw` blob + Stripe
   test keys bundled in assets — mixed, non-official build artifacts.

### Implication
We must **not** treat this binary as trustworthy or re-ship it. We use it **only** as a
*functional reference* (the feature set is fully recoverable from the manifest +
resources + class/type names). The rebuild (Doc 02) explicitly excludes the ad,
tracker, and redirect mechanisms above.

> Security note: installing this APK on a device is inadvisable — it is an untrusted,
> tampered binary that requests broad permissions (§7).

---

## 4. Technology stack (what it is built with)

### Language
- **Java** — dominant (legacy `com.estrongs.*`, `es.*` packages).
- **Kotlin** — present in newer modules (`com.esfile.screen.recorder.*`,
  `com.estrongs.android.pop.ktx`, `kotlinx.coroutines` service registrations).

### Core architecture: a Virtual Filesystem (VFS)
The heart of the app is `com.estrongs.fs` (≈ 561 classes) — a **pluggable filesystem
provider** system. Each `com.estrongs.fs.impl.*` package is one "mountable" root of the
browser. This is the single most important design idea to carry into the rebuild:

| Provider package | Mounts / capability |
|---|---|
| `fs.impl.local` | Local / internal / external storage |
| `fs.impl.usb` | USB / OTG / MTP |
| `fs.impl.netfs` | Generic network filesystem core |
| `fs.impl.smb` | SMB / CIFS (Windows shares) — `jcifs` + `com.hierynomus` (JcSMB) |
| `fs.impl.sftp` | SFTP / SSH — `com.jcraft` (JSch) |
| `fs.impl.ftp` | FTP **client** (and a built-in FTP **server**, `ESFtpService`) |
| `fs.impl.webdav` | WebDAV |
| `fs.impl.http` | HTTP |
| `fs.impl.adb` | ADB / USB-debug bridge |
| `fs.impl.bluetooth` | Bluetooth OBEX (`OBEXFtpServerService`) |
| `fs.impl.compress` | Archives as virtual folders (7-Zip / zip) |
| `fs.impl.archive` | Archive browsing |
| `fs.impl.media` | Media-indexed virtual folders |
| `fs.impl.gallery` / `music` / `video` / `picture` / `book` | Typed media views |
| `fs.impl.recycle` | Recycle bin |
| `fs.impl.encrypt` | **Encrypted folder** (BouncyCastle-backed) |
| `fs.impl.apk` | APK inspection |
| `fs.impl.appfolder` | App folder / favorites |
| `fs.impl.pcs` | Cloud / “netdisk” (see §5) |
| `fs.impl.search` / `searchbased` / `finder` | Search & smart find |
| `fs.impl.remotesite` | Remote site |

### Protocols & network
- `okhttp3` (HTTP client)
- `org.apache` (httpcomponents / commons)
- `com.nimbusds` — `nimbus-jose-jwt` (OAuth 2.0 / JWT for cloud drives)
- `javax.jmdns` — JmDNS (DLNA / UPnP service discovery)
- `com.hierynomus` (JcSMB) + `jcifs` — SMB/CIFS
- `com.jcraft` (JSch) — SFTP/SSH
- `org.simpleframework` — embedded HTTP/FTP **server** framework
- `org.teleal` (clink) — WebSocket
- `org.msgpack`, `com.fasterxml` (Jackson), `net.minidev` (json-smart), `com.alibaba`
  (fastjson) — serialization

### Cryptography
- **BouncyCastle** — `org.bouncycastle`, **3,642 classes**. Used for the encrypted
  folder/vault and assorted crypto.

### UI
- `androidx.*` — appcompat, recyclerview, constraintlayout, viewpager2, preference,
  mediarouter, transition, lifecycle, fragment.
- `com.bumptech` (Glide) + `com.nostra13` (Universal Image Loader) — image loading.
- `com.yalantis` (UCrop) — image cropping.
- `pl.droidsonroids` (GIF).
- Activity-centric (150 activities) — **not** modern Fragment/Compose composition.

### Payments (in-app purchase)
- `com.stripe` — **1,328 classes** (card + 3-D Secure 2 flow, `grs_*` config).
- `com.alipay` — Alipay.
- WeChat Pay — `com.estrongs.android.pop.wxapi` (`WXEntryActivity`, `WXPayEntryActivity`).

### Ads / analytics / telemetry (the part we will NOT rebuild)
- `com.umeng` (535), `com.baidu` (241), `com.tencent` (317 — Bugly + crash),
  `com.huawei` (849 — HMS/AGConnect), `com.google` (2,721 — GMS: Cast/SignIn/Ads),
  `com.estrongs.android.pop.algorix` (Algorix ad), xuanhu, `com.fun` (report/upgrade
  SDK), Huawei `updatesdk`.
- Native: `libBugly.so`, `libcrashsdk.so`, `libumeng-spy.so`.

### Native libraries (`lib/`)
| Library | Purpose |
|---|---|
| `lib7-Zip-JBinding.so` | 7-Zip archive engine |
| `libBugly.so` / `libcrashsdk.so` | Tencent crash reporting |
| `libpl_droidsonroids_gif.so` | GIF decoding |
| `libumeng-spy.so` | Umeng analytics |
| (assets) `res/raw/estool_arm_pie`, `estool_x86_pie` | small native “estool” helpers |

ABIs shipped: `armeabi`, `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

### Web / JS assets
- `assets/ace` — **Ace code editor** (106 JS files) + `assets/editor.html` → the app
  embeds a web-based code editor.
- `assets/es_privacy_content{,_en,_zh}.html` — privacy text (3 locales).
- `assets/templates/{docx,pptx,xlsx}.xlsx` — Office template files.
- `assets/search_float_property.json`, `supplierconfig.json`, `au_becs_bsb.json`
  (Stripe AU banking data), `grs_sdk_server_config.json`.
- `assets/folder_app.zip` — a bundled mini “folder app”.
- `assets/ds-visa.crt`, `ds-mastercard.crt` — **Stripe test** card certs.

---

## 5. Cloud (“netdisk”) integrations

`res/raw/netdisk_*` configures the cloud-drive providers (each an OAuth- or
API-key-backed remote FS mounted under `fs.impl.pcs`):

| Provider | Config |
|---|---|
| Dropbox | `netdisk_dropbox` (+ `com.dropbox` SDK, 1,764 classes) |
| Mega | `netdisk_megacloud` |
| Amazon S3 | `netdisk_s3` |
| SugarSync | `netdisk_sugarsync` |
| MediaFire | `netdisk_mediafire` |
| Boxnet | `netdisk_boxnet` |
| vDisk | `netdisk_vdisk` |

Login flows exist for Google (`GoogleWebSignIn`), Huawei HWID
(`com.huawei.hms.hwid`), WeChat, and Facebook (`com.facebook.sdk`).

---

## 6. Feature inventory (the usable part — what to rebuild)

Grouped by domain. ✅ = high value to rebuild, ◐ = selective, ✗ = do **not** rebuild.

### 6.1 Core file manager ✅
- Browse local + USB + network roots via the VFS (§4).
- Media “top classify” views (Images / Music / Video / Documents / Apps).
- Multi-window, left-navigation drawer, customizable themes, favorites, recycle bin.
- Search, fast scroller, drag & drop, floating window, gestures.
- Shortcuts, wallpaper picker, ringtone picker, content chooser (`GET_CONTENT`),
  directory picker, `CREATE_SHORTCUT`.
- Text / note editor (`com.jecelyin.editor` — the app is forked from **Je File
  Manager**), plus a web/Ace code editor.
- Disk-usage analyzer (`com.estrongs.android.analysis`, daily report).
- **Encrypted folder / vault** (BouncyCastle). ✅
- **Auto-backup** (`ui.autobackup`). ✅

### 6.2 Archives ✅
- View + create: `zip, rar, 7z, tar, gz, bz2, cab` (`CompressionActivity`,
  MIME list in manifest, 7-Zip native engine).

### 6.3 Media ✅
- **Video player** — local + streaming over `ftp/http/https/sftp`
  (`PopVideoPlayer`, `StreamingMediaPlayer`, Chromecast `PopChromecastPlayer`).
- **Audio player** — local + streaming (`PopAudioPlayer`, `AudioPlayerService`
  + `MediaButtonReceiver`).
- Image viewer + crop (`ViewImage21`, `CropImage`, UCrop).
- **Screen recorder + video editor** (`com.esfile.screen.recorder.*`): record,
  trim, rotate, crop, speed, background music, caption, intro/outro, picture,
  **GIF convert**, merge.
- Media pickers (`MediaPickerActivity`, `MusicPickerActivity`, `NewMediaPickerActivity`).

### 6.4 Network / remote ✅
- **FTP client + built-in FTP server** (`ESFtpService`, `FtpServerPreference`).
- SMB / CIFS, SFTP / SSH, WebDAV, HTTP, ADB.
- **Bluetooth OBEX** file transfer + server (`OBEXFtpServerService`).
- **DLNA / UPnP** — browse/cast to TV (`com.estrongs.dlna`, `DlnaUpnpService`,
  `DlnaDeviceFileSelectActivity`, `RequestCastScreenToTVActivityDialog`).
- **Chromecast** media cast.
- **Cloud drives** (§5).
- Local file sharing / sharing-to (`LocalFileSharingActivity`,
  `FileSharingNotificationActivity`, `SaveToESActivity` via `SEND`/`SEND_MULTIPLE`).

### 6.5 Office / documents ◐
- `docx/pptx/xlsx` template-based conversion, note editor, code editor.

### 6.6 Cleaner ◐
- Junk/ad-cache cleaner (`adjunk.txt` path list). *Rebuild as a generic,
  user-controlled cache cleaner — not tied to ad paths.*

### 6.7 App management ◐
- Install/uninstall monitor (`InstallMonitorReceiver`, `UninstallMonitorActivity`),
  APK inspection, app info.

### 6.8 Account / premium ◐ (rebuild **without** the ad layer)
- Login/register (Google / WeChat / etc.), account info, premium/subscription.

### 6.9 Monetization ✗ (replace with a clean model)
- Stripe / Alipay / WeChat Pay exist, but the app is wrapped in an **aggressive ad
  system**: splash-ad launcher, `ADUnlockActivity`, per-OEM ad supplier, xuanhu,
  Algorix, Umeng, Baidu, Huawei, GMS-Ads. **Rebuild with a single, honest
  one-time-purchase or subscription (e.g. Play Billing) and zero ad SDKs.**

### 6.10 System ✗
- “Performance accelerator” (`PerformanceAccelerateService`,
  `KILL_BACKGROUND_PROCESSES`) — a process-killer that is a privacy/UX hazard. **Do
  not rebuild.**

---

## 7. Permissions (41 declared) — and what they imply

Storage & media
- `READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `MANAGE_DOCUMENTS`,
  `WRITE_MEDIA_STORAGE` — full-device file access (the core need, but the app asks for
  the most powerful variant).

Network
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
  `CHANGE_NETWORK_STATE`, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `CHANGE_WIFI_MULTICAST_STATE`
  (DLNA).

Location
- `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` — **not needed for a file manager**;
  a red flag inherited from ad/telemetry SDKs.

Phone / system
- `READ_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`, `GET_ACCOUNTS`, `GET_TASKS`,
  `CLEAR_APP_CACHE`, `KILL_BACKGROUND_PROCESSES`, `REQUEST_INSTALL_PACKAGES`,
  `REQUEST_DELETE_PACKAGES`, `SET_WALLPAPER`, `SYSTEM_ALERT_WINDOW` (floating window),
  `VIBRATE`, `WAKE_LOCK`, `GET_PACKAGE_SIZE`, `ACCESS_SUPERUSER`.

Shortcuts
- `com.android.launcher.permission.INSTALL_SHORTCUT`, `UNINSTALL_SHORTCUT`.

Advertising / telemetry / push
- `com.google.android.gms.permission.AD_ID` (Google Ads ID),
  `com.google.android.c2dm.permission.RECEIVE` + `com.estrongs...C2D_MESSAGE` (GCM
  push), `com.huawei.appmarket...GET_COMMON_DATA`.

**Rebuild stance (§2 of Doc 02):** request the *minimum* (storage via SAF / scoped
storage, network only when a network root is used, no location, no `AD_ID`, no
`KILL_BACKGROUND_PROCESSES`, no `SUPERUSER`).

---

## 8. Weaknesses / smells to fix in the rebuild

| # | Issue | Rebuild response |
|---|---|---|
| 1 | Repacked, corrupted, untrusted binary | Build fresh from source; add reproducible-build + signing pipeline |
| 2 | `targetSdk 30` / `compileSdk 23` / `minSdk 19` (ancient) | `minSdk 26`, `target/compileSdk 35` |
| 3 | Broad permissions + heavy third-party trackers | Privacy-first, least-privilege, no ad SDKs |
| 4 | 150 activities (fragmented, Activity-centric) | Fewer, composable screens (Compose) + Fragments where needed |
| 5 | Legacy network libs (`jcifs`, old `JSch`, embedded server) | Modern, maintained libs (SMBJ, Apache MINA SSHD, okio/ktor) |
| 6 | Ad-injected search + OEM ad supplier | No third-party redirects; first-party search |
| 7 | Process-killer “performance accelerator” | Remove |
| 8 | Obfuscated, dual legacy (`es.*`, `com.estrongs.old`) | Clean, modular, documented codebase |
| 9 | V1/JAR signing only | V2/V3 APK signing |
| 10 | Bundled Stripe *test* keys + `trusted-certs.raw` | No bundled test credentials; proper secrets management |

---

## 9. Recovery notes (method)

Because the DEX ID tables are corrupted, the original **bytecode is not decompilable**.
The functional surface was recovered from the *intact* artifacts instead:

- `AndroidManifest.xml` (decoded from binary AXML) → activities, services, permissions,
  intent-filters, meta-data, signers.
- `resources.arsc` + `res/` → layout names (862), raw netdisk configs, tool binaries.
- DEX `string_ids` + `type_ids` pools (intact) → the full 33,371 class-name set, used to
  enumerate every module, protocol, SDK and feature above.

Scripts used (kept in `.apk-analysis/`): `analyze_manifest.py`, `analyze_dex.py`.
Outputs: `manifest.json`, `dex_classes.txt` (33,371 lines), `dex_summary.json`.
