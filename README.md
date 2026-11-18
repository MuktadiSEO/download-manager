# MRM Zone IDM — Internet Download Manager for Android

<img src="app/src/main/res/drawable-nodpi/logo_mrm.png" width="120" alt="MRM Zone logo">

An IDM-style download manager for Android, branded **MRM Zone**.

## Install
Download `release/MRM-Zone-IDM.apk` from this repository, open it on your phone and allow
"Install unknown apps" when asked. (Android 6.0 or newer.)

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
- **Built-in browser** with a "Download this video" button (detects video/audio on pages),
  intercepts downloads, long-press any link to download.
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
