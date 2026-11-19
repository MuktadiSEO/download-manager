// MRM Zone IDM Integration - content script.
// Shows an IDM-style "Download this video" panel over visible <video> elements,
// and displays toasts requested by the service worker.
(() => {
  'use strict';
  if (window.__mrmZoneIdmLoaded) return;
  window.__mrmZoneIdmLoaded = true;

  const MIN_W = 200;
  const MIN_H = 120;
  const Z = '2147483647';
  const NOT_RUNNING_MSG = 'MRM Zone IDM is not running. Please open the app.';

  let enabled = true;
  const panels = new Map();          // video -> { host, root, btn }
  const hiddenVideos = new WeakSet(); // closed with the "x" button

  const isYouTube = /(^|\.)youtube\.com$/i.test(location.hostname);
  const isYouTubeVideoPage = () =>
    isYouTube && (/^\/watch/.test(location.pathname) || /^\/shorts\//.test(location.pathname));

  // ---------------------------------------------------------------------------
  // Settings
  // ---------------------------------------------------------------------------
  function readSettings() {
    try {
      chrome.storage.local.get({ showVideoButton: true }, (s) => {
        if (chrome.runtime.lastError) return;
        setEnabled(s.showVideoButton !== false);
      });
    } catch (_) { /* extension reloaded */ }
  }
  try {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && changes.showVideoButton) {
        setEnabled(changes.showVideoButton.newValue !== false);
      }
    });
  } catch (_) { /* ignore */ }

  function setEnabled(v) {
    enabled = v;
    if (!enabled) {
      for (const video of Array.from(panels.keys())) removePanel(video);
    } else {
      scheduleScan();
    }
  }

  // ---------------------------------------------------------------------------
  // Panel (shadow DOM)
  // ---------------------------------------------------------------------------
  const PANEL_CSS = `
    :host { all: initial; }
    .wrap {
      display: inline-flex; align-items: center; gap: 4px;
      font-family: "Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif;
      line-height: 1; user-select: none; -webkit-user-select: none;
      opacity: .88; transition: opacity .2s ease;
    }
    .wrap:hover { opacity: 1; }
    .btn {
      all: unset; box-sizing: border-box; cursor: pointer;
      display: inline-flex; align-items: center; gap: 6px;
      height: 26px; padding: 0 11px 0 4px; border-radius: 13px;
      background: rgba(18, 20, 24, .72);
      -webkit-backdrop-filter: blur(8px); backdrop-filter: blur(8px);
      border: 1px solid rgba(255, 255, 255, .14);
      box-shadow: 0 2px 10px rgba(0, 0, 0, .28);
      color: #fff; font-size: 12px; font-weight: 600; letter-spacing: .1px;
      white-space: nowrap; transition: background .15s ease, border-color .15s ease, transform .1s ease;
    }
    .btn:hover { background: rgba(18, 20, 24, .9); border-color: rgba(255, 99, 71, .7); }
    .btn:active { transform: scale(.97); }
    .btn[disabled] { opacity: .7; cursor: progress; }
    .ico {
      width: 18px; height: 18px; border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center;
      background: #FF6347; color: #fff; flex: none;
    }
    .ico svg { width: 12px; height: 12px; display: block; }
    .close {
      all: unset; box-sizing: border-box; cursor: pointer;
      width: 18px; height: 18px; border-radius: 50%;
      display: inline-flex; align-items: center; justify-content: center;
      background: rgba(18, 20, 24, .72); border: 1px solid rgba(255, 255, 255, .14);
      color: rgba(255, 255, 255, .85); font-size: 12px; line-height: 1;
      opacity: 0; transition: opacity .15s ease;
    }
    .wrap:hover .close { opacity: 1; }
    .close:hover { background: rgba(18, 20, 24, .95); color: #fff; }
  `;

  function createPanel(video) {
    const host = document.createElement('mrm-idm-video-panel');
    host.setAttribute('data-mrm-idm', 'panel');
    host.style.cssText = [
      'position:fixed', 'top:0', 'left:0', `z-index:${Z}`, 'display:none',
      'margin:0', 'padding:0', 'border:0', 'width:auto', 'height:auto',
      'pointer-events:auto', 'transform:none', 'opacity:1', 'visibility:visible'
    ].map((d) => d + ' !important').join(';');
    const root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `
      <style>${PANEL_CSS}</style>
      <div class="wrap">
        <button class="btn" type="button" title="Download this video with MRM Zone IDM (all qualities)">
          <span class="ico"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5.5 19.5h13" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="label">Download video</span>
        </button>
        <button class="close" type="button" title="Hide">×</button>
      </div>`;
    const btn = root.querySelector('.btn');
    const label = root.querySelector('.label');
    const close = root.querySelector('.close');

    const stop = (e) => { e.stopPropagation(); };
    for (const ev of ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'dblclick', 'touchstart']) {
      host.addEventListener(ev, stop);
    }
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      onDownloadClick(video, btn, label);
    });
    close.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      hiddenVideos.add(video);
      removePanel(video);
    });

    const panel = { host, root, btn };
    panels.set(video, panel);
    attachHost(host, video);
    return panel;
  }

  // Fullscreen elements live in the top layer, so the host must be inside them.
  function containerFor(video) {
    const fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs && fs !== video && fs.contains(video) && fs.tagName !== 'VIDEO') return fs;
    return document.documentElement;
  }

  function attachHost(host, video) {
    const container = containerFor(video);
    if (host.parentNode !== container) {
      try { container.appendChild(host); } catch (_) { /* ignore */ }
    }
  }

  function removePanel(video) {
    const p = panels.get(video);
    if (!p) return;
    panels.delete(video);
    try { p.host.remove(); } catch (_) { /* ignore */ }
  }

  function onDownloadClick(video, btn, label) {
    const src = video.currentSrc || video.src || '';
    btn.disabled = true;
    const oldText = label.textContent;
    label.textContent = 'Sending…';
    const done = () => { btn.disabled = false; label.textContent = oldText; };
    try {
      chrome.runtime.sendMessage({
        type: 'mrm-video-click',
        src: /^https?:/i.test(src) ? src : '',
        frameUrl: location.href,
        title: document.title
      }, (res) => {
        const err = chrome.runtime.lastError;
        done();
        if (err || !res) { showToast(NOT_RUNNING_MSG, true); return; }
        if (!res.ok) showToast(res.message || NOT_RUNNING_MSG, true);
        else showToast('Sent to MRM Zone IDM', false);
      });
    } catch (_) {
      done();
      showToast('MRM Zone IDM extension was updated. Please reload the page.', true);
    }
  }

  // ---------------------------------------------------------------------------
  // Positioning
  // ---------------------------------------------------------------------------
  function isEligible(video) {
    if (!enabled || hiddenVideos.has(video) || !video.isConnected) return false;
    if (isYouTube) {
      // On YouTube only the main watch / shorts player gets the button.
      if (!isYouTubeVideoPage()) return false;
      if (!video.closest('#movie_player, #shorts-player, .html5-video-player, ytd-reel-video-renderer, #player')) return false;
    }
    const r = video.getBoundingClientRect();
    if (r.width < MIN_W || r.height < MIN_H) return false;
    const vw = window.innerWidth || document.documentElement.clientWidth;
    const vh = window.innerHeight || document.documentElement.clientHeight;
    if (r.bottom < 40 || r.top > vh - 30 || r.right < 60 || r.left > vw - 60) return false;
    const cs = getComputedStyle(video);
    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return false;
    return true;
  }

  function positionPanel(video, panel) {
    const r = video.getBoundingClientRect();
    const vw = window.innerWidth || document.documentElement.clientWidth;
    const host = panel.host;
    attachHost(host, video);
    host.style.setProperty('display', 'block', 'important');
    const w = host.offsetWidth || 200;
    // tucked into the video's top-right corner
    const inset = r.width > 640 ? 12 : 8;
    const top = Math.max(r.top, 0) + inset;
    const right = Math.min(r.right, vw) - inset;
    const left = Math.max(Math.max(r.left, 0) + 4, right - w);
    host.style.setProperty('top', `${Math.round(top)}px`, 'important');
    host.style.setProperty('left', `${Math.round(left)}px`, 'important');
  }

  function updateAll() {
    for (const [video, panel] of Array.from(panels)) {
      if (!isEligible(video)) {
        if (!video.isConnected || hiddenVideos.has(video) || !enabled) removePanel(video);
        else panel.host.style.setProperty('display', 'none', 'important');
        continue;
      }
      positionPanel(video, panel);
    }
  }

  function scan() {
    if (!enabled) return;
    let videos;
    try { videos = document.querySelectorAll('video'); } catch (_) { return; }
    for (const v of videos) {
      if (!panels.has(v) && isEligible(v)) createPanel(v);
    }
    updateAll();
  }

  // Throttled scheduling
  let rafPending = false;
  function scheduleUpdate() {
    if (rafPending) return;
    rafPending = true;
    requestAnimationFrame(() => { rafPending = false; try { updateAll(); } catch (_) { /* ignore */ } });
  }
  let scanTimer = null;
  let lastScan = 0;
  function scheduleScan() {
    if (scanTimer) return;
    const wait = Math.max(0, 400 - (Date.now() - lastScan));
    scanTimer = setTimeout(() => {
      scanTimer = null;
      lastScan = Date.now();
      try { scan(); } catch (_) { /* ignore */ }
    }, wait);
  }

  window.addEventListener('scroll', scheduleUpdate, { capture: true, passive: true });
  window.addEventListener('resize', scheduleUpdate, { passive: true });
  document.addEventListener('fullscreenchange', () => { scheduleUpdate(); scheduleScan(); });
  document.addEventListener('webkitfullscreenchange', () => { scheduleUpdate(); scheduleScan(); });
  for (const ev of ['loadedmetadata', 'play', 'playing', 'resize']) {
    document.addEventListener(ev, (e) => {
      if (e.target && e.target.tagName === 'VIDEO') scheduleScan();
    }, true);
  }

  try {
    const mo = new MutationObserver((mutations) => {
      for (const m of mutations) {
        if (m.type === 'attributes' || m.addedNodes.length || m.removedNodes.length) { scheduleScan(); return; }
      }
    });
    mo.observe(document.documentElement || document, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class', 'src', 'hidden']
    });
  } catch (_) { /* ignore */ }

  // Layout can change without scroll events (animations, SPA navigation).
  setInterval(() => {
    if (document.hidden) return;
    if (panels.size) scheduleUpdate();
    scheduleScan();
  }, 1000);

  // ---------------------------------------------------------------------------
  // Toast
  // ---------------------------------------------------------------------------
  let toastHost = null;
  let toastTimer = null;
  function showToast(text, isError) {
    try {
      if (!toastHost) {
        toastHost = document.createElement('mrm-idm-toast');
        toastHost.style.cssText = [
          'position:fixed', 'right:20px', 'bottom:20px', `z-index:${Z}`, 'display:block',
          'margin:0', 'padding:0', 'border:0', 'pointer-events:none'
        ].map((d) => d + ' !important').join(';');
        const root = toastHost.attachShadow({ mode: 'open' });
        root.innerHTML = `
          <style>
            :host { all: initial; }
            .t {
              display: flex; align-items: center; gap: 10px; max-width: 360px;
              padding: 12px 16px; border-radius: 10px; background: #2B2F36; color: #fff;
              font: 500 13px/1.4 "Segoe UI", Tahoma, Arial, sans-serif;
              box-shadow: 0 6px 24px rgba(0,0,0,.35); border-left: 4px solid #FF6347;
              opacity: 0; transform: translateY(8px); transition: opacity .2s, transform .2s;
            }
            .t.show { opacity: 1; transform: none; }
            .t.ok { border-left-color: #2E7D32; }
            .b { background: #FF6347; color: #fff; border-radius: 6px; padding: 3px 6px; font-weight: 800; font-size: 10px; }
          </style>
          <div class="t"><span class="b">MRM</span><span class="m"></span></div>`;
      }
      const fs = document.fullscreenElement;
      const container = fs && fs.tagName !== 'VIDEO' ? fs : document.documentElement;
      if (toastHost.parentNode !== container) container.appendChild(toastHost);
      const root = toastHost.shadowRoot;
      const box = root.querySelector('.t');
      root.querySelector('.m').textContent = text;
      box.classList.toggle('ok', !isError);
      requestAnimationFrame(() => box.classList.add('show'));
      clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        box.classList.remove('show');
        setTimeout(() => { try { toastHost.remove(); } catch (_) { /* ignore */ } }, 250);
      }, 4000);
    } catch (_) { /* ignore */ }
  }

  try {
    chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
      if (msg && msg.type === 'mrm-toast') {
        showToast(msg.text || NOT_RUNNING_MSG, true);
        sendResponse({ ok: true });
      }
      return false;
    });
  } catch (_) { /* ignore */ }

  readSettings();
  scheduleScan();
})();
