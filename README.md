# Paradox Downloader (source)

Private source for [Paradox Downloader](https://github.com/Paradox-ultra/paradox-downloader).
Rebuilt in v4.3 from the v4.2 release after the original source was lost; see
[docs/AUDIT.md](docs/AUDIT.md) for what was fixed.

- Package: `bot.paradox.downloader` · minSdk 26 · targetSdk 35 · Java 17
- UI: Material 3 (Material You colours on Android 12+), RecyclerView with DiffUtil
- Video/audio sites: NewPipe Extractor (progressive streams)

## Layout

| File | What it does |
|---|---|
| `JobStore` | All downloads, in memory, saved to `jobs.json` at most once a second |
| `DownloadService` | Foreground service: queue, workers, progress ticker, notifications |
| `ChunkEngine` | Multi-connection download in 2 MB pieces with a resume journal |
| `Resolver`, `MirrorParser` | Turn a pasted link into downloadable options |
| `MainActivity`, `JobAdapter` | Main screen and download cards |
| `BrowserActivity` | Built-in browser fallback with "Scan links" |
| `UpdateChecker`, `ChannelCard` | Update prompt and WhatsApp channel invite |

## Building

Push to `main` and the **Build APK** workflow builds and signs the release APK
(artifact on the run). Signing uses four repository secrets:
`PD_KEYSTORE_B64`, `PD_KEYSTORE_PASSWORD`, `PD_KEY_ALIAS`, `PD_KEY_PASSWORD`.
Keep a backup of the keystore: every future update must be signed with it.

Locally: Android Studio, or `gradle :app:assembleRelease` with JDK 17 and the
same values exported as `PD_KEYSTORE` (path), `PD_KEYSTORE_PASSWORD`,
`PD_KEY_ALIAS`, `PD_KEY_PASSWORD`.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. Run **Build APK** with *publish* ticked. The run prints a temporary link and the SHA-256.
3. In the public repo, create the release with `versionCode: N` in its notes
   (the in-app update check reads it), then run **Attach release asset** with
   that link, the file name and the SHA-256.
