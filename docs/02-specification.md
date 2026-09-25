# Document 02 — Product & Technical Specification (Rebuild from Scratch)

Companion to **Doc 01** (APK analysis). This is the working specification for the new
project: a modern, privacy-first, ad-free file manager, media suite, and network drive
client for Android, using the analyzed ES File Explorer build **only as a functional
reference** (feature ideas) — never as code or assets.

---

## 1. Vision & principles

> **A clean, fast, privacy-respecting file manager that also understands media,
> archives, and every major file protocol — with zero ads and zero trackers.**

Principles (each maps to a weakness found in Doc 01):

1. **Privacy first** — no ad SDKs, no telemetry, no third-party trackers, no
   third-party redirect URLs. Telemetry is opt-in, self-hosted, and default-off.
2. **Least privilege** — minimum permissions, runtime requests with human-readable
   rationale (3 languages), Android scoped-storage/SAF preferred over
   `MANAGE_EXTERNAL_STORAGE` (which we request *only* on the explicit "full disk
   access" opt-in path, like modern file managers).
3. **Modern baseline** — Kotlin, Jetpack Compose (Material 3), coroutines/Flow, Hilt.
4. **Modular** — multi-module Gradle; every protocol and media feature is an
   independent module that can be enabled/disabled.
5. **Protocol-first core** — the Virtual Filesystem (VFS) is the spine (the single
   best design idea from the reference app): one browser UI, N filesystem providers.
6. **Reproducible & signed** — V2/V3 signing, deterministic builds, no bundled test
   secrets.
7. **Testable** — protocol modules are pure JVM where possible (unit-tested without
   devices); UI covered by Compose + Espresso.

### Non-goals (explicit)
- ❌ Ad networks, ad splash screens, "unlock ads", OEM ad mediation.
- ❌ Location-based tracking; no location permission at all (v1).
- ❌ Background process killer / "accelerator".
- ❌ Re-using any code, resources, or strings from the reference APK (IP + the binary
  is untrusted; Doc 01 §3).
- ❌ TV/Wear OS support in v1 (the reference had `leanback` + `watch` res variants;
  we keep the code architecture *compatible* with them later).

---

## 2. Platform targets

| Property | Value | Rationale |
|---|---|---|
| Language | Kotlin 100% | reference mixes Java/Kotlin; new code should be uniform |
| `minSdk` | **26** (Android 8.0) | covers ~99% of devices; enables modern storage + notification APIs |
| `targetSdk` / `compileSdk` | **35** (Android 15) | current |
| UI | Jetpack Compose + Material 3, dark theme, **full RTL support** (reference shipped `ldrtl` variants — Arabic matters for our audience), tablet layouts |
| Storage | SAF + scoped storage; `MANAGE_EXTERNAL_STORAGE` only via explicit opt-in screen |
| Concurrency | coroutines + Flow, WorkManager for transfers/scans |
| DI | Hilt |
| Packaging | V2+V3 signing, ABI splits, app bundle |
| i18n | en + ar (RTL) + zh (parity with reference), locale resources from day one |
| Build | Gradle (KTS) + version catalog; reproducible build flags |

---

## 3. Product scope & prioritization

Priorities are derived from Doc 01 §6 (✅/◐/✗), de-duplicated and re-ranked.

### P0 — MVP (the file manager core)
| ID | Feature | Reference evidence (Doc 01) |
|---|---|---|
| P0-1 | Browse local storage (internal, SD, `Android/data`) | `fs.impl.local` |
| P0-2 | VFS: unified root list (Storage / Media / Recent / Downloads / Favorites / Archives / Network) | `com.estrongs.fs` |
| P0-3 | File operations: copy/move/delete/rename/new folder/multi-select, context menu | `ui.menu` (327 classes) |
| P0-4 | List/grid view, sort, search (local), fast scroller | `ui.topclassify`, `fastscroller` |
| P0-5 | Recycle bin (delayed delete) | `fs.impl.recycle` |
| P0-6 | Archive **browse** (zip/7z/tar/rar/gz) | `CompressionActivity`, 7-Zip |
| P0-7 | Image viewer + pinch zoom | `ViewImage21` |
| P0-8 | Settings: themes (light/dark/system), layout prefs, storage stats | `ui.theme` |
| P0-9 | Share out (Android share sheet) + open-with | `SEND` filters |
| P0-10 | Disk usage stats (per folder) | `SHOW_DISK_USAGE` |

### P1 — first differentiators
| ID | Feature | Notes |
|---|---|---|
| P1-1 | Archive **create + extract** (zip/7z/tar/gz/bz2; rar view-only) | lib7-Zip-JBinding → modern `sevenzipjbinding` fork or `Apache Commons Compress` + native 7z |
| P1-2 | **Encrypted vault** (AES-256-GCM container, PBKDF2/scrypt KDF, password + biometric unlock) | `fs.impl.encrypt` + BouncyCastle concept; our own container format |
| P1-3 | **SMB/CIFS** client (SMB2/3) | `fs.impl.smb` → SMBJ (modern, maintained) |
| P1-4 | **SFTP** client | `fs.impl.sftp` → Apache MINA SSHD |
| P1-5 | **FTP client + FTP/SFTP server** (LAN file sharing, QR-code access) | `ESFtpService` + `FtpServerPreference` concept |
| P1-6 | **WebDAV** client | `fs.impl.webdav` |
| P1-7 | **Video player** (Media3/ExoPlayer: DASH, HLS, subtitles, gesture controls) | `PopVideoPlayer` |
| P1-8 | **Audio player** (playlists, lock-screen controls) | `PopAudioPlayer` |
| P1-9 | Downloads manager (pause/resume/multi-part) | `DownloaderActivity`, `BrowserDownloaderActivity` |
| P1-10 | Media library views (Images/Music/Videos/Documents) with thumbnails | `ui.topclassify`, `scanner` (171 classes) |
| P1-11 | Text & Markdown editor | `PopNoteEditor` / jecelyin fork → our own editor |
| P1-12 | Share-receive (become a `GET_CONTENT` / file-chooser target; `SEND`/`SEND_MULTIPLE` "Save here") | chooser filters |
| P1-13 | Auto-backup of chosen folders to a local destination (WorkManager, scheduling) | `ui.autobackup` |

### P2 — expanded surface (roadmap, post-1.0)
| ID | Feature | Notes |
|---|---|---|
| P2-1 | Cloud drives: **S3-compatible** (first-class), Dropbox, Mega, pCloud/Nextcloud — via a `CloudProvider` plugin interface | `fs.impl.pcs` + `netdisk_*` concept; OAuth with nimbus-jose-jwt |
| P2-2 | **DLNA/UPnP** browsing + cast; Chromecast (Media3 cast) | `com.estrongs.dlna` |
| P2-3 | Bluetooth OBEX receive/send | `fs.impl.bluetooth` |
| P2-4 | Screen recorder + basic video editing (trim/rotate/caption/GIF export) | `com.esfile.screen.recorder` |
| P2-5 | Office: open docx/xlsx/pptx (read), export text | `templates/*` concept |
| P2-6 | USB/OTG browsing | `fs.impl.usb` |
| P2-7 | App manager (list, storage per app, uninstall intent) | `appinfo` |
| P2-8 | Customizable home page (widgets: quick roots, recent, stats) | `ui.homepage` |
| P2-9 | Tablet/multi-window layout + desktop mode (split pane) | `MultiWindowActivity` |
| P2-10 | Optional paid "Pro" unlock via **Play Billing** (one-time or yearly); everything else free, forever | replaces Stripe/Alipay/WeChat + ad model |

### Explicitly dropped (Doc 01 §6 ✗)
Ad system, splash ads, `ADUnlock`, OEM ad supplier, proxy search engines, process
killer, GCM push (use FCM *only* for the user's own cross-device sync in P2, opt-in),
`ACCESS_SUPERUSER` path.

---

## 4. Architecture

### 4.1 Module layout (Gradle)

```
app/                          # composition root, manifests, flavors (free/pro? no—single)
core/
  core-model/                 # domain models: FileNode, FsUri, TransferTask, VaultEntry…
  core-common/                # utilities, logging, error model, coroutines scope owners
  core-database/              # Room (recent files, favorites, transfers, vault index)
  core-security/              # Keystore, crypto (BouncyCastle-free Tink), vault container
  core-network/               # HttpClient (okhttp/ktor), TLS, proxy settings
  core-file/                  # ★ VFS core: FsProvider SPI, FsRegistry, path resolution
feature/
  feature-home/               # root grid (Storage/Media/Recent/Network/Vault/…)
  feature-filemanager/        # ★ browser: list/grid, actions, context menu, search
  feature-imageviewer/        # viewer + zoom (+crop in P1)
  feature-video/              # Media3 player UI
  feature-audio/              # player UI, playlists
  feature-editor/             # text/markdown editor
  feature-archives/           # archive UI (browse/create/extract)
  feature-vault/              # encrypted folder UI + unlock flow
  feature-network/            # connection manager UI (SMB/SFTP/FTP/WebDAV/HTTP + server)
  feature-cloud/              # cloud provider UI (P2)
  feature-transfers/          # downloads + transfer station
  feature-settings/           # preferences, storage stats, privacy, about
  feature-stats/              # disk usage analyzer (P0-10)
providers/                    # ★ one module per protocol (each self-contained)
  provider-local/             # SAF + direct storage
  provider-usb/               # (P2)
  provider-smb/               # SMBJ
  provider-sftp/              # MINA SSHD
  provider-ftp/               # client
  provider-ftpsrv/            # embedded server (P1-5)
  provider-webdav/
  provider-http/
  provider-archive/           # zip/7z/tar/gz virtual FS + create/extract
  provider-media/             # MediaStore-backed virtual roots
  provider-vault/             # encrypted container FS
  provider-cloud/             # CloudProvider SPI + s3/dropbox/mega adapters (P2)
design-system/                # Compose theme, components, RTL-safe tokens
```

Dependency rule: `feature-*` depends on `core-file` (the SPI) but **never** on
`provider-*` directly; `app` wires providers into the registry (compile-time
plugin list, runtime discovery optional).

### 4.2 The VFS core (`core-file`) — the heart

One abstraction, N backends (this is the design lesson from the reference's
`com.estrongs.fs`, modernized):

```kotlin
// core-file: stable SPI
interface FsProvider {
    val type: FsType                      // LOCAL, SMB, SFTP, FTP, WEBDAV, HTTP, ARCHIVE, MEDIA, VAULT, CLOUD
    suspend fun root(uri: Uri): FsNode    // connect/mount
    suspend fun list(node: FsNode): Flow<FsNode>        // paginated
    suspend fun metadata(node: FsNode): FsMetadata
    fun open(node: FsNode): Flow<ByteReadSource>         // read stream (okio)
    fun write(node: FsNode, source: ByteWriteSource): Flow<Progress>
    suspend fun create(node: FsNode, kind: FsKind): FsNode
    suspend fun rename(node: FsNode, newName: String): FsNode
    suspend fun delete(node: FsNode, force: Boolean)
    val capabilities: Set<FsCapability>   // READ, WRITE, CREATE_DIR, RENAME, TRIM, …
}

data class FsNode(
    val id: String,            // provider-stable id
    val uri: Uri,              // content://fsm/<type>/<opaque>
    val name: String,
    val parent: FsNode?,
    val children: List<FsNode>? = null,
    val metadata: FsMetadata? = null,
    val providerType: FsType,
)
```

- All URIs are `content://` via a single `FileContentProvider` (mirrors the reference's
  `FileContentProvider`, but built on `DocumentFile`-style stable docs).
- `FsRegistry` maps `scheme → FsProvider`; `FsUriParser` handles deep links
  (`esm://browse?smb=host/share`).
- Every protocol module implements `FsProvider` against real network in integration
  tests (Testcontainers for FTP/SMB servers where possible).

### 4.3 State & data flow
- MVVM + unidirectional data flow: `Screen (Compose) → ViewModel (state holders) →
  Repository (use cases) → FsProvider/Room`.
- `StateFlow<BrowserState>` per tab; multi-pane via tab state objects (matches the
  reference's multi-window feature).
- Room for: recents, favorites, transfer queue, vault index, connection profiles.
- Media scans run in WorkManager, write to Room + thumbnail cache (Coil custom
  `DataSource` over `FsProvider` → any protocol gets thumbnails for free).

### 4.4 Key library choices (replacing the reference's stack)
| Concern | Reference (Doc 01) | Rebuild |
|---|---|---|
| HTTP | okhttp3 (old) | Ktor client (or OkHttp 4.x) |
| SMB | jcifs + JcSMB (ancient) | **SMBJ** (SMB2/3, maintained) |
| SFTP | JSch (abandoned) | **Apache MINA SSHD** |
| FTP server | org.simpleframework | Apache FtpServer (embedded) |
| Archives | 7-Zip-JBinding | **sevenzipjbinding** + Commons Compress (fallback) |
| OAuth | nimbus-jose-jwt | nimbus-jose-jwt (keep — it's good) |
| Crypto | BouncyCastle (3.6k classes) | **Tink** (AES-GCM, scrypt) + Keystore; BouncyCastle only if Tink lacks something |
| Images | Glide + UIL + droidsonroids | **Coil 3** (+ its GIF) |
| Player | custom MediaPlayer stack | **Media3** (ExoPlayer, cast, session) |
| UI | appcompat + 150 activities | Compose + Material 3, ~20 screens |
| DI/misc | — | Hilt, Coroutines, WorkManager, DataStore |

### 4.5 Deep links & intents (parity with reference manifest)
| Intent we handle | Purpose |
|---|---|
| `VIEW` `resource/folder` + `VIEW_DIRECTORY` (OpenIntents) | open folder in browser |
| `GET_CONTENT` (`*/*`) + `OPEN_DOCUMENT_TREE` fallback | file picker for other apps |
| `SEND` / `SEND_MULTIPLE` (`*/*`) | “Save to <app>” |
| `VIEW` `file://`,`content://` for audio/video/image/text MIME | play/open |
| `VIEW` `http(s)://`,`ftp://`,`sftp://` media/download | stream or download |
| `RINGTONE_PICKER`, `SET_WALLPAPER` (P1+) | chooser parity |
| `CREATE_SHORTCUT` | launcher shortcuts |
| Custom `esm://browse?…` | deep links to roots/connections |

---

## 5. Data model (core-model)

```kotlin
enum class FsType { LOCAL, USB, MEDIA, RECENT, FAVORITE, ARCHIVE, VAULT,
                    SMB, SFTP, FTP, FTPSRV, WEBDAV, HTTP, CLOUD }
enum class FsCapability { READ, WRITE, CREATE, CREATE_DIR, RENAME, DELETE,
                          RANGE_READ, RESUME_DOWNLOAD, STREAM }
data class FsMetadata(
    val sizeBytes: Long?, val modifiedAt: Instant?,
    val isDirectory: Boolean, val mimeType: String?,
    val permissions: Set<Perm>?, val owner: String?,
    val etag: String?, val extra: Map<String,String> = emptyMap(),
)
data class TransferTask(
    val id: UUID, val kind: Kind /* DOWNLOAD, UPLOAD, COPY, MOVE, EXTRACT, CREATE_ARCHIVE */,
    val from: FsNode?, val to: FsNode, val state: State /* QUEUED|RUNNING|PAUSED|DONE|FAILED */,
    val progress: Double, val error: String?,
)
data class VaultContainer(path, kdf: Kdf /* SCRYPT(n,r,p,salt) */, aead: "AES-256-GCM",
                          chunkSize, indexVersion)
```

Room tables: `recents(node_id, fs_uri, last_seen)`, `favorites`, `transfers`,
`connections(profile, type, host, auth_ref)`, `vault_index`.

---

## 6. Security & privacy spec

1. **No third-party identifiers.** No ad-ID, no vendor SDKs, no fingerprinting.
   Device ID = locally generated, never transmitted.
2. **Opt-in telemetry only** (P2): self-hosted PostHog-compatible endpoint, off by
   default, user can clear.
3. **Credentials** (SMB/SFTP/FTP/cloud) stored in Android Keystore-backed
   `EncryptedSharedPreferences` (or DataStore + Tink); never in logs.
4. **Vault**: container format `v1` — AES-256-GCM, 4 MiB chunks, scrypt KDF
   (n=2^15, r=8, p=1), 32-byte salt, per-chunk nonce = counter||random12;
   container metadata in a 64 KiB header (HMAC-SHA256 over header).
   (BouncyCastle-backed in the reference; we use Tink + BouncyCastle `PBE` only
   where needed.)
5. **TLS**: default system trust + optional user-imported CA for private SMB/SFTP/
   WebDAV servers (no bundled `trusted-certs.raw` nonsense).
6. **Exports**: `FileContentProvider` grants short-lived URIs with
   `FLAG_GRANT_READ_URI_PERMISSION` only.
7. **Threat model note**: the app is a file manager — its top risk is accidental
   destructive ops → recycle bin + confirm dialogs + per-folder undo where cheap.

### Permission matrix (final)
| Permission | When |
|---|---|
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` | media library views (runtime) |
| `WRITE_EXTERNAL_STORAGE` (≤Q) / SAF only (≥Q) | saving new files |
| `MANAGE_EXTERNAL_STORAGE` | **only** after explicit “Full disk access” opt-in screen |
| `INTERNET` | only needed for network roots; app works fully offline otherwise |
| `BLUETOOTH*` / `BLUETOOTH_CONNECT` (P2 OBEX) | runtime, only on demand |
| `FOREGROUND_SERVICE_DATA_SYNC` | active transfers |
| `POST_NOTIFICATIONS` | transfer progress (runtime) |
| `USE_BIOMETRIC` (P1 vault) | optional unlock |
| ❌ `ACCESS_FINE/COARSE_LOCATION`, `READ_PHONE_STATE`, `AD_ID`, `KILL_BACKGROUND_PROCESSES`, `SYSTEM_ALERT_WINDOW`* | *floating mini-player only (P2, optional) |

---

## 7. UI / UX spec (high level)

- **Structure**: bottom bar [Home · Files · Media · Network · More]; each top level is
  a tab with its own back stack; tablet → split pane (browse | preview).
- **Home**: root grid (Internal, SD, Downloads, Media, Recent, Favorites, Archives,
  Vault, Network connections), storage stat card.
- **Browser**: breadcrumb + address bar (editable), list/grid toggle, sort, search
  bar with scope (name / type / size / date), long-press multi-select, action bar
  (open, preview, copy, move, delete, rename, share, info, add to vault/favorites).
- **Media**: Photos grid with month headers; Video list with duration/codec chips;
  player with gesture controls + subtitles (Media3).
- **Network**: connections list + “Add” (SMB / SFTP / FTP / WebDAV / HTTP / Cloud);
  each saved profile is a root in Files; FTP/SFTP **server** screen with QR code of
  the LAN URL + token.
- **Themes**: Material 3 dynamic color (Android 12+) with manual palette fallback;
  full dark; **RTL first-class** (Arabic UI).
- **Empty/error states**: every protocol gets a first-class error (auth, timeout,
  permission) with retry — the reference had 291 dialog classes; we do this with ~15
  composable error components.
- **Accessibility**: full content-descriptions, min touch targets, screen-reader
  pass in CI.

---

## 8. Testing & quality

| Layer | Tool | Coverage target |
|---|---|---|
| Unit (model, VFS SPI, crypto, path logic) | JUnit5 + MockK + Turbine | 90% on `core-*` |
| Protocol (real servers) | JUnit + Testcontainers (FTP: `FtpServer`, SFTP: sshd, SMB: Samba container, WebDAV: `jackrabbit-webdav`) | every `provider-*` |
| UI | Compose `createComposeRule` + Espresso (SAF flows) | all P0 screens |
| E2E smoke | Roborazzi screenshots + manual matrix (API 26/33/35, light/dark, LTR/RTL) | release gate |
| Performance | macrobenchmark: cold start < 1.5 s (P90), 10k-file folder scroll 60 fps | release gate |
| Security | OSS dependencies scan (Dependency-Check), no-SDK lint rule (ban ad SDKs by name) | CI gate |
| Reproducibility | `--rerun-tasks` diff check for `core-file` artifacts | CI |

---

## 9. Roadmap (milestones)

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M0 — Scaffold (wk 1–2)** | Gradle setup, module skeleton, design-system, CI (lint/test/build/sign), README | empty app builds, lints, signs, installs |
| **M1 — Local MVP (wk 3–7)** | VFS core + `provider-local` + browser UI (P0-1…P0-10), image viewer, settings, recents/favorites | daily-drivable local file manager |
| **M2 — Archives & Media (wk 8–12)** | archive browse/create, MediaStore library views, video+audio players, downloads (P1-1,7,8,9,10) | play any local media; zip a folder |
| **M3 — Network (wk 13–18)** | SMB, SFTP, FTP client + server, WebDAV, connection profiles, transfers (P1-3…P1-6) | move files LAN↔device over 4 protocols |
| **M4 — Vault & Editor (wk 19–22)** | vault (P1-2), text/markdown editor (P1-11), auto-backup (P1-13), share-receive intents (P1-12) | encrypted folder round-trip; choose-file-for-apps |
| **M5 — Hardening & 1.0 (wk 23–26)** | perf, a11y, i18n (ar/zh), benchmark gates, store listing, reproducible signing | v1.0 release |
| **P2 backlog** | cloud (S3→Dropbox→Mega), DLNA/cast, screen recorder, Office view, tablet mode, Pro billing | post-1.0 trains |

---

## 10. Open questions (need product decisions)

1. **App name & identity** — “Ee” (repo name) or a new brand? (Affects package name
   `app.<brand>.…` and store listing.)
2. **Monetization** — free + one-time Pro unlock via Play Billing (recommended), or
   subscription, or fully free?
3. **Vault KDF params & container format** — OK to define our own `v1` format (Doc
   §6.4) or must it be compatible with the reference's encrypted folders?
   (Recommendation: own format — the reference binary is untrusted anyway.)
4. **Cloud first target** — S3-compatible (covers MinIO/Backblaze etc.) as P2-1
   first, or a specific consumer drive?
5. **Screen recorder / video editor** — keep in P2 (recommended: it's a big scope) or
   drop entirely?
6. **Distribution** — Play Store + F-Droid (flatpak-style APK, no billing) or
   APK-only site? (Affects the Pro-billing module design.)
7. **Repo strategy for the reference APK** — keep it in git history (it's 37 MB), or
   move it to a GitHub Release / LFS and replace the tracked blob with a pointer?
   (Recommendation: move to Release + `.gitattributes` LFS; do not add more binaries.)

---

*Source of truth for “what the reference does”: `docs/01-apk-analysis.md`.
Raw extraction artifacts: `.apk-analysis/` (git-ignored; regenerate with the two
scripts if needed).*
