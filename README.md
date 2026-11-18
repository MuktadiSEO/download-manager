# MRM Zone IDM — Internet Download Manager for Android

<img src="app/src/main/res/drawable-nodpi/logo_mrm.png" width="120" alt="MRM Zone logo">

An IDM-style download manager for Android, branded **MRM Zone**.

## Install
Download `release/MRM-Zone-IDM-arm64.apk` (almost all phones from the last ~8 years) or
`release/MRM-Zone-IDM-armv7.apk` (older 32-bit phones), open it and allow "Install unknown apps".
Android 7.0 or newer.

## Video download (YouTube, Facebook, Instagram, TikTok and 1000+ sites)
- Paste/share a video link, or tap **Download video** in the built-in browser → title, thumbnail and
  **every quality**: 144p … 720p, 1080p, 1440p (2K), 2160p (4K), 60fps, plus **Audio (M4A)** and **MP3**.
- Powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp) + ffmpeg (video and audio are merged automatically).
  The engine updates itself daily; Options → *Update video engine now* forces an update.

## Features (like desktop IDM)
- **Multi-connection downloads** — up to 32 connections per file, with **dynamic segmentation**
  (when one part finishes, the largest remaining part is split and a new connection takes over).
- **Pause / Resume**, automatic retry on network errors, resume after app restart / reboot.
- **Download progress window** with per-connection progress bar, speed, time left, resume capability.
- **Download complete dialog** (Open / Folder / Close).
- **Tabs**: All, Active, Completed, Error, Unfinished, Queue + **categories**
  (Video, Music, Documents, Compressed, Programs, Images, General) — files are auto-sorted into
  `Download/MRM Zone/<Category>`.
- **IDM toolbar**: Add URL, Resume, Stop, Stop All, Delete, Delete Completed, Options, Scheduler,
  Start Queue, Stop Queue, Grabber, Batch, Browser, Tell a Friend.
- **Queue + Scheduler** — start / stop the main queue at set times on chosen days.
- **Speed limiter** — global and per download.
- **Built-in browser** with **multiple tabs**, a **Downloads** button (shows active count),
  a list of **all downloadable files on any page**, video quality picker, desktop mode,
  long-press any link to download / open in new tab.
- **Smart Add URL**: video pages show the quality list; other web pages open in the browser
  with their downloadable files listed.
- **Site Grabber** — scan a web page (optionally the pages it links to) for files of chosen types.
- **Batch download** — `https://site.com/file*.jpg` with number/letter ranges.
- **Clipboard monitoring** and **"Share → Download with MRM Zone IDM"** from any app.
- **Refresh download address** (Properties → paste a new link when the old one expired).
- Notifications with progress and "Pause all", Wi-Fi-only mode, light/dark theme.

## Build
```
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also builds it on every push.
