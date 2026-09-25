# Ee — File Manager, rebuilt from scratch

A modern, **privacy-first, ad-free** file manager, media suite, and network-drive
client for Android. The reference build
[`مستكشف ملفات ES_4.4.2.2.1.apk`](مستكشف%20ملفات%20ES_4.4.2.2.1.apk) (ES File
Explorer 4.4.2.2.1) is used **only as a functional reference** — see the analysis.

> **تنبيه:** ملف الـ APK في هذا المستودع هو نسخة **معدّلة/معاد تغليفها** (repacked)
> — جداول dex فيها تالفة، وتحتوي على إعلانات وإعادة توجيه. لا تثبّت الملف. نستخدمه
> مرجعًا وظيفيًا فقط، والكود الجديد يُكتب من الصفر.

## Status

| Stage | State |
|---|---|
| 1. Reverse-engineer the reference APK (manifest, dex, resources, assets) | ✅ done |
| 2. Specification (scope, architecture, modules, security, roadmap) | ✅ done |
| 3. M0 — project scaffold (33 modules, VFS core + tests, design system, CI) | ✅ done |
| 4. M1 — local file manager MVP (browser, local provider, trash, Room, settings) | ✅ done |
| 5. M2 — archives + media (players, downloads, media library) | ✅ done |
| 6. M3 — network drives (SMB/SFTP/FTP/WebDAV/HTTP) + background transfers | ✅ done |
| 7. M4 — vault + permission-aware UX (P0-1…P0-4) | ⏳ next |
| 8. M5 — P2 tail: USB, cloud push, SFTP key auth, directory copy/move, 7z/RAR | ⏳ |
| 9. M6 — polish, battery/storage audit, beta (v1.0 candidate) | ⏳ |

### M2 highlights (v0.3.0-m2)

- **Archives** — `provider-archive` browses zip/tar/tar.gz/gz/bz2 as virtual
  folders (tap an archive in the browser), creates zips, and extracts with
  zip-slip/tar-slip guards. 7z/RAR land in M5 (native engine).
- **Media library** — `provider-media` exposes MediaStore (Images/Videos/Audio)
  through the VFS; `feature-home` shows a tabbed library with Coil thumbnails.
- **Players** — Media3 ExoPlayer: video (PlayerView + controller) and audio
  (play/pause/seek) screens; image viewer with full-screen display.
- **Downloads** — transfer station with add/pause/resume/cancel, HTTP Range
  resume (206 → append, 200 → restart), queue persisted in Room (v2, real
  1→2 migration). WorkManager background execution lands in M3.
- **Recent files** on home, recorded as files are opened.

### M3 (done)

- **Network providers** — SMB (SMBJ), SFTP (MINA SSHD), FTP (Commons Net),
  WebDAV (PROPFIND over OkHttp), HTTP (stream + autoindex listing).
- **Connection manager** — saved profiles (Room) + add/delete UI; passwords
  encrypted with an Android-Keystore-wrapped AES-256-GCM box
  (`core-security`, unit-tested primitives); never stored in plaintext.
- **Copy/move** (P0-5) — app-wide clipboard, copy/move in the selection bar,
  paste into any directory (local↔network).
- **Background downloads** (P1-9) — WorkManager worker drives the download
  engine (HTTP Range resume); the queue lives in Room, so transfers survive
  configuration changes and process death.
- **Lock-screen audio** (P1-8) — ExoPlayer behind a `MediaSession`
  (`media3-session`): system media controls while the app is in background.
- **LAN sharing / embedded FTP server** (P1-5) — Apache FtpServer rooted at
  external storage with a guest account; start/stop from the Network screen,
  share URL + scannable QR code (ZXing) so any device can open it with one
  scan.

## Building

Requirements: **JDK 17**, Android SDK (`platforms;android-35`). The Gradle
wrapper is committed; the first build downloads the distribution and
dependencies (network access to Maven Central / Google Maven required).

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests (VFS core is pure JVM)
./gradlew lint                   # static analysis
python3 tools/check_scaffold.py  # offline scaffold consistency check
```

### Project layout

```
app/                        # composition root (wires providers into the VFS registry)
core/core-model/            # domain models (FsNode, FsMetadata, TransferTask, Vault) — pure JVM
core/core-file/             # ★ VFS SPI: FsProvider, FsRegistry, FsUri — pure JVM + tests
core/core-common|network/   # utilities, HTTP factory — pure JVM
core/core-database|security/# Room, Keystore/Tink (Android)
design-system/              # Compose Material 3 theme (dark + dynamic color, RTL)
feature/*                   # one module per screen area (home, browser, media, …)
providers/*                 # one module per protocol (local, smb, sftp, ftp, …)
tools/check_scaffold.py     # offline consistency checker (CI-free)
```

## Documentation

- **[`docs/01-apk-analysis.md`](docs/01-apk-analysis.md)** — full analysis of the
  reference APK: identity, signing, scale, tech stack, complete feature inventory,
  permissions, and the provenance findings (repacked build).
- **[`docs/02-specification.md`](docs/02-specification.md)** — the rebuild
  specification: principles, P0/P1/P2 scope, module layout, VFS core design,
  data model, security/privacy spec, testing, roadmap, open questions.

## Reference APK — key facts (summary)

- Package `com.estrongs.android.pop`, v4.4.2.2.1 (code 15036), minSdk 19, targetSdk 30
- 33,371 unique classes across 4 DEX files, 150 activities, 862 layouts
- Core design: a **Virtual Filesystem** with providers for local, USB, SMB, SFTP,
  FTP (client **and** server), WebDAV, HTTP, ADB, Bluetooth OBEX, archives, media,
  recycle bin, and an **encrypted folder** (BouncyCastle)
- Media: video/audio players (local + streaming), image viewer, screen recorder +
  video editor + GIF
- Cloud: Dropbox, Mega, S3, SugarSync, MediaFire, Boxnet, vDisk
- Payments: Stripe / Alipay / WeChat — wrapped in an aggressive ad + tracker stack
  (Umeng, Baidu, Huawei, GMS-Ads, Algorix, xuanhu) that the rebuild **excludes**
- ⚠️ The DEX ID tables are corrupted and `assets/` contains ad-redirect search
  URLs and OEM ad-supplier config → this binary was repacked after signing.
  **Do not install it.**

## Notes

- `.apk-analysis/` (git-ignored) holds the extraction + analysis scripts and raw
  outputs; regenerate anytime with `analyze_manifest.py` / `analyze_dex.py`.
- The 37 MB reference APK should ideally move to a GitHub Release + Git LFS —
  see open question #7 in `docs/02-specification.md`.
