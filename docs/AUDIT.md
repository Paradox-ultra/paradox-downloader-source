# Paradox Downloader v4.2 audit

The v4.2 source was lost, so this audit was done on the released APK
(`Paradox_Downloader_v4.2.apk`, SHA-256 `daadfb73…701e`, versionCode 6) by
decompiling it and checking each finding against the bytecode. v4.3 is a clean
rewrite that keeps every feature and fixes all of the issues below.

## Bugs

| # | Area | Bug | Effect |
|---|------|-----|--------|
| 1 | List | Every progress update re-saves the job at the end of the stored list, and the list is drawn newest-first by position. | The download that reported last jumps to the top. With two downloads they swap places continuously, and every swap rebuilds all cards (lag, flicker, mis-taps). |
| 2 | Engine | Progress callbacks read the job, change it and save it back outside the lock. | Tapping **Pause** or **Cancel** mid-download can be overwritten back to "downloading". |
| 3 | Engine | After probing a link, the job object read *before* probing is saved with state "downloading". | Pause or Cancel tapped while "Connecting" is ignored; a cancelled download starts anyway. |
| 4 | Resume | The resume journal is keyed to the final redirected URL. CDN links (Drive, Pixeldrain, YouTube, most mirrors) redirect to a new signed URL every time. | Resume silently restarts from 0 % for most links. |
| 5 | Names | Downloads are queued with an empty name and rely on the server's file name. YouTube and SoundCloud stream URLs end in `/videoplayback` and send no name. | Videos and audio save as `videoplayback` with no extension; the phone can't open them. |
| 6 | Speed | One shared, unsynchronized speed counter is updated by up to 12 threads, and progress is reported only once per 2 MB piece. | Speed and time-left jump around or show nonsense. |
| 7 | Storage | Each progress tick rewrites the whole job list to disk synchronously (`commit()`) and broadcasts to the UI. | Heavy disk I/O and constant re-renders while downloading. |
| 8 | Restart | When Android kills the app, only jobs in state "downloading" are resumed. | Jobs that were "queued" or "connecting" stay stuck forever. |
| 9 | Pause | Links without range support restart from zero on resume, but the card says "Tap resume to continue". | Misleading; progress lost. |
| 10 | Gofile | Uses a hard-coded website token and never sends an account token cookie. | Gofile links fail to download. |
| 11 | Drive | Uses the old `uc?export=download` flow. Large files now need confirmation on `drive.usercontent.google.com`. | Large Drive files end in "No direct file found". |
| 12 | Links | Video-site detection matches anywhere in the URL (e.g. `?ref=youtube.com`). | Unrelated links are sent to the YouTube extractor and fail. |
| 13 | Names | `filename*=` values are URL-decoded with `URLDecoder`, which turns `+` into spaces. | File names with `+` are changed. |
| 14 | Updates | The update checker calls `api.github.com/repos/chinmays-tanx09/…`, the old account name. | Works only through GitHub's rename redirect. If anyone registers that name, they control what the app offers as an update. |
| 15 | Format | Sizes above 1 GB are shown in MB in notifications. | "3072.0 MB" instead of "3.0 GB". |
| 16 | UI | Continuous decorative animations (animated background, shimmer button, ticker) plus a forced high refresh rate. | Battery drain and stutter on mid-range phones. |
| 17 | Links | Vimeo and Dailymotion links are sent to NewPipe Extractor, which doesn't support those sites. | They always fail. v4.3 only sends links NewPipe actually accepts, and tries everything else as a normal link. |

## Features kept in v4.3

- Paste or share any link (share sheet, open-with, clipboard).
- Smart resolver: direct files, Google Drive, Dropbox, OneDrive/SharePoint,
  Pixeldrain, MediaFire, Catbox, Gofile, mirror pages (server buttons, meta
  refresh, JavaScript redirects, GET forms), and YouTube / SoundCloud / Bandcamp /
  PeerTube via NewPipe Extractor (single-file streams).
- Server picker when a page has several mirrors.
- Built-in browser fallback for countdown / sign-in pages, with "Scan links".
- Multi-connection downloads (1-16 connections, default 8, 2 MB pieces), pause,
  resume, cancel, retry.
- Saves to `Download/Paradox Downloader` (MediaStore on Android 10+).
- Open, share, delete file, remove from list. APK install permission prompt.
- Foreground notification with progress; completion and failure notices.
- Battery-optimisation prompt (once), notification permission (Android 13+).
- WhatsApp channel card (Join / Later / Don't show again), now at most once a day
  instead of on every app start.
- Update check (every 12 hours, Download / Later / Skip this version).
