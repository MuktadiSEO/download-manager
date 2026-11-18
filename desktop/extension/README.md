# MRM Zone IDM Integration (Chrome / Edge extension)

Sends Chrome/Edge downloads, detected videos and page links to the MRM Zone IDM
desktop app (local API on `http://127.0.0.1:47321`, falling back to ports 47322-47325).

## Install

**Chrome**
1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top-right).
3. Click **Load unpacked** and select this `extension` folder.

**Microsoft Edge**
1. Open `edge://extensions`.
2. Turn on **Developer mode** (left sidebar).
3. Click **Load unpacked** and select this `extension` folder.

Start MRM Zone IDM; the toolbar popup shows "Connected to MRM Zone IDM" when the app is reachable.
If the app is not running, Chrome downloads normally.
