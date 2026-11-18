// MRM Zone IDM Integration - popup.
'use strict';

const DEFAULTS = { captureEnabled: true, showVideoButton: true, minSizeKB: 0 };
const $ = (id) => document.getElementById(id);
let currentTab = null;

function send(msg) {
  return new Promise((resolve) => {
    try {
      chrome.runtime.sendMessage(msg, (res) => {
        if (chrome.runtime.lastError) resolve(null);
        else resolve(res);
      });
    } catch (_) {
      resolve(null);
    }
  });
}

function formatSize(bytes) {
  if (!bytes || bytes <= 0) return 'size unknown';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  let n = bytes;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function displayName(url) {
  try {
    const u = new URL(url);
    const last = decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || '');
    return { name: last || u.hostname, host: u.hostname };
  } catch (_) {
    return { name: url, host: '' };
  }
}

function typeLabel(m) {
  if (m.kind === 'hls') return 'HLS';
  if (m.kind === 'dash') return 'DASH';
  const t = (m.type || '').split('/')[1] || 'file';
  return t.replace(/^x-/, '').slice(0, 10);
}

let messageTimer = null;
function showMessage(text, ok) {
  const el = $('message');
  el.textContent = text;
  el.classList.toggle('ok', !!ok);
  el.hidden = false;
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => { el.hidden = true; }, 4000);
}

function handleResult(res) {
  if (res && res.ok) showMessage('Sent to MRM Zone IDM', true);
  else showMessage((res && res.message) || 'MRM Zone IDM is not running. Please open the app.', false);
}

async function withBusy(button, fn) {
  button.disabled = true;
  try { await fn(); } finally { button.disabled = false; }
}

// --- connection status ------------------------------------------------------
async function refreshStatus() {
  const box = $('status');
  const res = await send({ type: 'mrm-status' });
  box.classList.remove('checking', 'ok', 'bad');
  if (res && res.connected) {
    box.classList.add('ok');
    $('statusText').textContent = 'Connected to MRM Zone IDM' + (res.version ? ` ${res.version}` : '');
  } else {
    box.classList.add('bad');
    $('statusText').textContent = 'App not running';
  }
}

// --- settings ---------------------------------------------------------------
async function loadSettings() {
  let s = DEFAULTS;
  try { s = { ...DEFAULTS, ...(await chrome.storage.local.get(DEFAULTS)) }; } catch (_) { /* ignore */ }
  $('captureEnabled').checked = s.captureEnabled !== false;
  $('showVideoButton').checked = s.showVideoButton !== false;
  $('minSizeKB').value = String(Math.max(0, Number(s.minSizeKB) || 0));
}

function bindSettings() {
  $('captureEnabled').addEventListener('change', (e) => {
    chrome.storage.local.set({ captureEnabled: e.target.checked }).catch(() => {});
  });
  $('showVideoButton').addEventListener('change', (e) => {
    chrome.storage.local.set({ showVideoButton: e.target.checked }).catch(() => {});
  });
  $('minSizeKB').addEventListener('change', (e) => {
    const v = Math.max(0, Math.floor(Number(e.target.value) || 0));
    e.target.value = String(v);
    chrome.storage.local.set({ minSizeKB: v }).catch(() => {});
  });
}

// --- media list -------------------------------------------------------------
function renderMedia(media) {
  const list = $('mediaList');
  list.textContent = '';
  $('mediaCount').textContent = String(media.length);
  $('mediaEmpty').hidden = media.length > 0;
  for (const m of media.slice().reverse()) {
    const { name, host } = displayName(m.url);
    const li = document.createElement('li');
    li.className = 'media-item';

    const info = document.createElement('div');
    info.className = 'media-info';
    const n = document.createElement('div');
    n.className = 'media-name';
    n.textContent = name;
    n.title = m.url;
    const meta = document.createElement('div');
    meta.className = 'media-meta';
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = typeLabel(m);
    meta.append(tag, document.createTextNode(`${formatSize(m.size)} · ${host}`));
    info.append(n, meta);

    const btn = document.createElement('button');
    btn.className = 'btn small';
    btn.type = 'button';
    btn.textContent = 'Download';
    btn.addEventListener('click', () => withBusy(btn, async () => {
      handleResult(await send({ type: 'mrm-download-media', tabId: currentTab.id, item: m }));
    }));

    li.append(info, btn);
    list.appendChild(li);
  }
}

async function refreshMedia() {
  if (!currentTab) { renderMedia([]); return; }
  const res = await send({ type: 'mrm-get-media', tabId: currentTab.id });
  renderMedia((res && Array.isArray(res.media)) ? res.media : []);
}

// --- init -------------------------------------------------------------------
async function init() {
  try { $('extVersion').textContent = 'v' + chrome.runtime.getManifest().version; } catch (_) { /* ignore */ }
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    currentTab = tab || null;
  } catch (_) { currentTab = null; }

  const webPage = !!(currentTab && /^https?:/i.test(currentTab.url || ''));
  $('allLinks').disabled = !webPage;
  $('pageVideo').disabled = !webPage;

  $('allLinks').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
    handleResult(await send({ type: 'mrm-download-links', tabId: currentTab.id }));
  }));
  $('pageVideo').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
    handleResult(await send({ type: 'mrm-download-page-video', tabId: currentTab.id }));
  }));

  bindSettings();
  await loadSettings();
  refreshMedia();
  refreshStatus();
}

document.addEventListener('DOMContentLoaded', () => { init().catch(() => {}); });
