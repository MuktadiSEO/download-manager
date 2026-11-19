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
- **Ad & pop-up blocker** (EasyList + IndianList, easylist.to, GPLv3 / CC BY-SA 3.0): blocks ad scripts,
  pop-up / pop-under tabs and ad redirects; shield button shows the count and can disable it per site.
- Browser tabs are saved and restored; ✕ closes only the current tab.
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

---

# MRM Zone IDM for Windows (PC)

`release/MRM-Zone-IDM-Setup-1.3.0.exe` — installer for Windows 10/11 64-bit (no admin rights needed).

## Install
1. Run **MRM-Zone-IDM-Setup-1.3.0.exe** → Next → Install → Finish (it launches the app).
   Windows SmartScreen may say "Windows protected your PC" because the installer is not code-signed:
   click **More info → Run anyway**.
2. On first start the app shows **"Click to install the browser extension"**:
   open Chrome's extensions page → turn on **Developer mode** → drag the extension folder
   (button "Open extension folder") onto the page, or click **Load unpacked** and select it.
   Chrome does not allow programs to install extensions silently, so this one-time step is manual.
3. The video engine (yt-dlp + FFmpeg + Deno, ~200 MB) downloads automatically in the background on
   first start; the first video download waits for it.

## Features
- IDM-style window: Add URL, Resume, Stop, Stop All, Delete, Delete Completed, Options, Scheduler,
  Start/Stop Queue, Grabber, Batch, categories tree (Video, Music, Documents, Compressed, Programs, Images),
  Unfinished / Finished / Queue views, search, tray icon, start with Windows.
- Up to 32 connections per file with dynamic segmentation, pause/resume, resume after crash or reboot,
  per-download and global speed limiter, auto retry, refresh expired download address.
- Download progress window with per-connection segment bar; "Download complete" dialog.
- **Video**: YouTube, Facebook, Instagram, TikTok and 1000+ sites — every quality 144p … 4K/8K, 60fps,
  M4A / MP3, with estimated sizes; HLS/DASH streams merged into one MP4.
- **Browser extension** (Chrome & Edge, `desktop/extension`): catches browser downloads,
  "Download this video" button on every video, right-click "Download with MRM Zone IDM",
  "Download all links", popup with detected videos.
- Clipboard monitoring, site grabber, batch download (`file*.jpg`), scheduler with shutdown-when-done.
- Files are saved to `Downloads\MRM Zone\<Category>`.

## Build
Requires the .NET 8 SDK and NSIS: `desktop/build.sh` (outputs `release/MRM-Zone-IDM-Setup-<version>.exe`).
