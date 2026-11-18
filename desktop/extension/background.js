// MRM Zone IDM Integration - background service worker (Manifest V3).
// All listeners are registered synchronously at top level so Chrome can wake
// the worker for them. Per-tab state lives in chrome.storage.session.

const APP_NAME = 'MRM Zone IDM';
const PORTS = [47321, 47322, 47323, 47324, 47325];
const PING_TIMEOUT_MS = 800;
const POST_TIMEOUT_MS = 5000;
const PING_CACHE_MS = 3000;
const MAX_MEDIA_PER_TAB = 50;
const MIN_MEDIA_BYTES = 300 * 1024;
const BADGE_COLOR = '#FF6347';
const SKIP_TTL_MS = 60 * 1000;

const DEFAULT_SETTINGS = {
  captureEnabled: true,
  showVideoButton: true,
  minSizeKB: 0
};

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function quiet(promiseOrFn) {
  try {
    const p = typeof promiseOrFn === 'function' ? promiseOrFn() : promiseOrFn;
    if (p && typeof p.catch === 'function') p.catch(() => {});
  } catch (_) { /* ignore */ }
}

async function getSettings() {
  try {
    const s = await chrome.storage.local.get(DEFAULT_SETTINGS);
    return { ...DEFAULT_SETTINGS, ...s };
  } catch (_) {
    return { ...DEFAULT_SETTINGS };
  }
}

function isHttpUrl(u) {
  return typeof u === 'string' && /^(https?|ftp):\/\//i.test(u);
}

function fileNameFromUrl(u) {
  try {
    const url = new URL(u);
    const last = url.pathname.split('/').filter(Boolean).pop() || '';
    const name = decodeURIComponent(last);
    return name || url.hostname;
  } catch (_) {
    return '';
  }
}

function baseName(p) {
  if (!p) return '';
  const parts = String(p).split(/[\\/]/);
  return parts[parts.length - 1] || '';
}

async function getCookieHeader(url) {
  if (!isHttpUrl(url)) return '';
  try {
    const cookies = await chrome.cookies.getAll({ url });
    return cookies.map((c) => `${c.name}=${c.value}`).join('; ');
  } catch (_) {
    return '';
  }
}

async function getTabSafe(tabId) {
  if (typeof tabId !== 'number' || tabId < 0) return null;
  try {
    return await chrome.tabs.get(tabId);
  } catch (_) {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Desktop app API client (port discovery + JSON calls)
// ---------------------------------------------------------------------------

let currentPort = null;        // module cache; also mirrored in storage.session
let lastPingOk = 0;            // timestamp of last successful contact
let lastPingInfo = null;

async function fetchJson(url, options, timeoutMs) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { ...options, signal: ctrl.signal, cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    try { return text ? JSON.parse(text) : {}; } catch (_) { return {}; }
  } finally {
    clearTimeout(timer);
  }
}

async function pingPort(port) {
  try {
    const info = await fetchJson(`http://127.0.0.1:${port}/ping`, { method: 'GET' }, PING_TIMEOUT_MS);
    if (info && typeof info === 'object' && (!info.app || String(info.app).includes('MRM'))) {
      return info;
    }
  } catch (_) { /* not there */ }
  return null;
}

async function rememberPort(port) {
  currentPort = port;
  quiet(chrome.storage.session.set({ appPort: port }));
}

async function loadRememberedPort() {
  if (currentPort) return currentPort;
  try {
    const { appPort } = await chrome.storage.session.get('appPort');
    if (PORTS.includes(appPort)) currentPort = appPort;
  } catch (_) { /* ignore */ }
  return currentPort;
}

// Returns {port, info} or null. Tries the remembered port first, then all ports in order.
async function discoverApp(force = false) {
  const remembered = await loadRememberedPort();
  if (!force && remembered && Date.now() - lastPingOk < PING_CACHE_MS) {
    return { port: remembered, info: lastPingInfo };
  }
  const order = remembered ? [remembered, ...PORTS.filter((p) => p !== remembered)] : PORTS;
  for (const port of order) {
    const info = await pingPort(port);
    if (info) {
      lastPingOk = Date.now();
      lastPingInfo = info;
      await rememberPort(port);
      return { port, info };
    }
  }
  lastPingOk = 0;
  lastPingInfo = null;
  return null;
}

async function isAppRunning() {
  return !!(await discoverApp(false));
}

// POST JSON to the app. Retries once with a fresh port discovery if the call fails.
async function callApp(path, body) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const found = await discoverApp(attempt > 0);
    if (!found) return { ok: false, error: 'not_running' };
    try {
      const res = await fetchJson(`http://127.0.0.1:${found.port}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }, POST_TIMEOUT_MS);
      lastPingOk = Date.now();
      return { ok: true, response: res };
    } catch (_) {
      lastPingOk = 0; // force re-discovery
    }
  }
  return { ok: false, error: 'request_failed' };
}

// ---------------------------------------------------------------------------
// Skip list (URLs we deliberately let Chrome download, to avoid capture loops)
// ---------------------------------------------------------------------------

const skipUrls = new Map(); // url -> expiry timestamp (module cache, mirrored to session)

async function loadSkip() {
  try {
    const { skipUrls: stored } = await chrome.storage.session.get('skipUrls');
    if (stored && typeof stored === 'object') {
      for (const [u, exp] of Object.entries(stored)) if (!skipUrls.has(u)) skipUrls.set(u, exp);
    }
  } catch (_) { /* ignore */ }
}

function pruneSkip() {
  const now = Date.now();
  for (const [u, exp] of skipUrls) if (exp < now) skipUrls.delete(u);
}

async function addSkip(url) {
  if (!url) return;
  skipUrls.set(url, Date.now() + SKIP_TTL_MS);
  pruneSkip();
  quiet(chrome.storage.session.set({ skipUrls: Object.fromEntries(skipUrls) }));
}

async function consumeSkip(url) {
  await loadSkip();
  pruneSkip();
  if (url && skipUrls.has(url)) {
    skipUrls.delete(url);
    quiet(chrome.storage.session.set({ skipUrls: Object.fromEntries(skipUrls) }));
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// 1. Download interception
// ---------------------------------------------------------------------------

const handledDownloadIds = new Set();

async function handleDownloadCreated(item) {
  try {
    if (!item || handledDownloadIds.has(item.id)) return;
    handledDownloadIds.add(item.id);
    if (item.state && item.state !== 'in_progress') return;
    if (item.byExtensionId && item.byExtensionId === chrome.runtime.id) return;

    const url = item.finalUrl || item.url;
    if (!isHttpUrl(url)) return; // skips blob:, data:, file:, chrome: ...
    if (await consumeSkip(url) || (item.url !== url && await consumeSkip(item.url))) return;

    const settings = await getSettings();
    if (!settings.captureEnabled) return;

    const app = await discoverApp(false);
    if (!app) return; // app not running: let Chrome continue normally

    // Refresh the item: by now Chrome usually knows size and file name.
    let fresh = item;
    try {
      const found = await chrome.downloads.search({ id: item.id });
      if (found && found[0]) fresh = found[0];
    } catch (_) { /* ignore */ }
    if (fresh.state && fresh.state !== 'in_progress') return; // finished or cancelled already

    const sizeCandidates = [fresh.totalBytes, fresh.fileSize, item.totalBytes, item.fileSize]
      .filter((n) => typeof n === 'number' && n > 0);
    const fileSize = sizeCandidates.length ? sizeCandidates[0] : 0;
    const minBytes = Math.max(0, Number(settings.minSizeKB) || 0) * 1024;
    if (minBytes > 0 && fileSize > 0 && fileSize < minBytes) return;

    // Stop Chrome's own download.
    try { await chrome.downloads.cancel(item.id); } catch (_) { /* ignore */ }
    try { await chrome.downloads.erase({ id: item.id }); } catch (_) { /* ignore */ }

    const referer = fresh.referrer || item.referrer || '';
    let fileName = baseName(fresh.filename);
    // Ignore temporary/GUID names Chrome may use before the real name is known.
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(fileName) || /\.crdownload$/i.test(fileName)) fileName = '';
    fileName = fileName || fileNameFromUrl(url);
    const tab = await findTabForUrl(referer);

    const body = {
      url,
      referer,
      cookies: await getCookieHeader(url),
      userAgent: navigator.userAgent,
      fileName,
      pageUrl: (tab && tab.url) || referer,
      fileSize
    };
    const result = await callApp('/add', body);
    if (!result.ok) {
      // The app went away between ping and POST: give the download back to Chrome.
      await addSkip(url);
      quiet(chrome.downloads.download({ url, conflictAction: 'uniquify' }));
    }
  } catch (_) {
    /* never throw */
  } finally {
    if (handledDownloadIds.size > 500) handledDownloadIds.clear();
  }
}

async function findTabForUrl(pageUrl) {
  try {
    const [active] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (!pageUrl) return active || null;
    if (active && active.url === pageUrl) return active;
    const tabs = await chrome.tabs.query({});
    return tabs.find((t) => t.url === pageUrl) || active || null;
  } catch (_) {
    return null;
  }
}

chrome.downloads.onCreated.addListener((item) => { quiet(handleDownloadCreated(item)); });

// ---------------------------------------------------------------------------
// 2. Media detection via webRequest (observation only)
// ---------------------------------------------------------------------------

const MEDIA_EXT = /\.(m3u8|mpd|mp4|webm|mkv|mov|flv|mp3|m4a|m4v|ogg|ogv|wav|3gp)$/i;
const SEGMENT_EXT = /\.(ts|m4s|aac|m4f|cmfv|cmfa|vtt|key|init)$/i;
const SEGMENT_WORD = /(segment|frag|chunk|seg-\d|\/seg\d|range\/)/i;
// Params removed from the URL that is sent to the app (they request only a byte range).
const RANGE_PARAMS = ['range', 'bytestart', 'byteend'];
// Extra params ignored only when de-duplicating.
const DEDUPE_PARAMS = ['rn', 'rbuf', 'start', 'end', 'begin', '_'];
const IGNORED_HOSTS = /(^|\.)googlevideo\.com$/i;

function headerValue(headers, name) {
  if (!headers) return '';
  const lower = name.toLowerCase();
  for (const h of headers) if (h.name && h.name.toLowerCase() === lower) return h.value || '';
  return '';
}

function stripParams(u, params) {
  try {
    const url = new URL(u);
    for (const p of params) url.searchParams.delete(p);
    url.hash = '';
    return url.toString();
  } catch (_) {
    return u;
  }
}

function normalizeMediaUrl(u) {
  return stripParams(u, RANGE_PARAMS);
}

function mediaKeyOf(u) {
  return stripParams(u, RANGE_PARAMS.concat(DEDUPE_PARAMS));
}

function guessTypeFromExt(path) {
  const m = /\.([a-z0-9]+)$/i.exec(path || '');
  const ext = m ? m[1].toLowerCase() : '';
  const map = {
    m3u8: 'application/vnd.apple.mpegurl', mpd: 'application/dash+xml',
    mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', mkv: 'video/x-matroska',
    mov: 'video/quicktime', flv: 'video/x-flv', mp3: 'audio/mpeg', m4a: 'audio/mp4',
    ogg: 'audio/ogg', ogv: 'video/ogg', wav: 'audio/wav', '3gp': 'video/3gpp'
  };
  return map[ext] || '';
}

function kindFor(contentType, path) {
  const ct = (contentType || '').toLowerCase();
  if (ct.includes('mpegurl') || /\.m3u8$/i.test(path)) return 'hls';
  if (ct.includes('dash+xml') || /\.mpd$/i.test(path)) return 'dash';
  return 'file';
}

// Returns a media descriptor or null.
function classifyResponse(details) {
  if (details.tabId < 0) return null;
  if (details.statusCode && (details.statusCode < 200 || details.statusCode >= 300)) return null;
  if (!isHttpUrl(details.url)) return null;
  if (details.initiator && details.initiator.startsWith('chrome-extension://')) return null;

  let url;
  try { url = new URL(details.url); } catch (_) { return null; }
  if (IGNORED_HOSTS.test(url.hostname)) return null;

  const path = url.pathname;
  const ctRaw = headerValue(details.responseHeaders, 'content-type');
  const ct = ctRaw.split(';')[0].trim().toLowerCase();

  const isPlaylistCt = ct === 'application/vnd.apple.mpegurl' || ct === 'application/x-mpegurl' ||
    ct === 'audio/mpegurl' || ct === 'audio/x-mpegurl' || ct === 'application/dash+xml';
  const isMediaCt = ct.startsWith('video/') || ct.startsWith('audio/');
  const isMediaExt = MEDIA_EXT.test(path);
  if (!isPlaylistCt && !isMediaCt && !isMediaExt) return null;

  // Ignore HLS / DASH segments and fragment requests.
  if (ct === 'video/mp2t' || ct === 'video/iso.segment' || ct === 'audio/aac') return null;
  if (SEGMENT_EXT.test(path)) return null;

  // Size: prefer the total from Content-Range (206 responses), else Content-Length.
  let size = 0;
  const cr = headerValue(details.responseHeaders, 'content-range');
  const crMatch = /\/(\d+)\s*$/.exec(cr);
  if (crMatch) size = parseInt(crMatch[1], 10) || 0;
  if (!size) size = parseInt(headerValue(details.responseHeaders, 'content-length'), 10) || 0;

  const kind = kindFor(ct, path);
  if (kind === 'file') {
    if (size > 0 && size < MIN_MEDIA_BYTES) return null;
    if (SEGMENT_WORD.test(details.url) && (size === 0 || size < 5 * 1024 * 1024)) return null;
  }

  const referer = details.documentUrl || details.initiator || '';
  return {
    url: normalizeMediaUrl(details.url),
    key: mediaKeyOf(details.url),
    type: ct && ct !== 'application/octet-stream' && ct !== 'binary/octet-stream' ? ct : (guessTypeFromExt(path) || ct || ''),
    size,
    referer,
    kind,
    frameId: details.frameId,
    t: Date.now()
  };
}

// Per-tab media list, persisted in storage.session under "media_<tabId>".
// Writes are serialized per tab to avoid read-modify-write races.
const tabLocks = new Map();

function withTabLock(tabId, fn) {
  const prev = tabLocks.get(tabId) || Promise.resolve();
  const next = prev.then(fn, fn).catch(() => {});
  tabLocks.set(tabId, next);
  next.then(() => { if (tabLocks.get(tabId) === next) tabLocks.delete(tabId); });
  return next;
}

const mediaKey = (tabId) => `media_${tabId}`;

async function getTabMedia(tabId) {
  try {
    const key = mediaKey(tabId);
    const data = await chrome.storage.session.get(key);
    return Array.isArray(data[key]) ? data[key] : [];
  } catch (_) {
    return [];
  }
}

async function setTabMedia(tabId, list) {
  try {
    if (list.length) await chrome.storage.session.set({ [mediaKey(tabId)]: list });
    else await chrome.storage.session.remove(mediaKey(tabId));
  } catch (_) { /* ignore */ }
  await updateBadge(tabId, list.length);
}

async function updateBadge(tabId, count) {
  try {
    await chrome.action.setBadgeText({ tabId, text: count > 0 ? String(count) : '' });
    if (count > 0) await chrome.action.setBadgeBackgroundColor({ tabId, color: BADGE_COLOR });
    if (count > 0 && chrome.action.setBadgeTextColor) {
      await chrome.action.setBadgeTextColor({ tabId, color: '#FFFFFF' });
    }
  } catch (_) { /* tab closed */ }
}

function addMedia(tabId, media, needFrameUrl) {
  return withTabLock(tabId, async () => {
    if (needFrameUrl) {
      // No documentUrl in the event: use the URL of the frame that loaded the media.
      try {
        const frame = await chrome.webNavigation.getFrame({ tabId, frameId: media.frameId || 0 });
        if (frame && isHttpUrl(frame.url)) media.referer = frame.url;
      } catch (_) { /* ignore */ }
    }
    const list = await getTabMedia(tabId);
    const idx = list.findIndex((m) => (m.key || mediaKeyOf(m.url)) === media.key);
    if (idx >= 0) {
      const old = list[idx];
      list[idx] = { ...old, size: media.size || old.size, type: media.type || old.type };
    } else {
      list.push(media);
      while (list.length > MAX_MEDIA_PER_TAB) list.shift();
    }
    await setTabMedia(tabId, list);
  });
}

function clearMedia(tabId, keepUrl) {
  return withTabLock(tabId, async () => {
    const list = await getTabMedia(tabId);
    const kept = keepUrl ? list.filter((m) => (m.key || mediaKeyOf(m.url)) === mediaKeyOf(keepUrl)) : [];
    if (kept.length !== list.length || list.length === 0) await setTabMedia(tabId, kept);
  });
}

chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    try {
      const media = classifyResponse(details);
      if (media) quiet(addMedia(details.tabId, media, !details.documentUrl));
    } catch (_) { /* ignore */ }
  },
  { urls: ['<all_urls>'] },
  ['responseHeaders']
);

// Clear the tab's list whenever its main frame navigates to a new document.
chrome.webNavigation.onCommitted.addListener((details) => {
  if (details.frameId !== 0 || details.tabId < 0) return;
  // Keep the entry for a directly opened media URL (its response arrives before commit).
  quiet(clearMedia(details.tabId, details.url));
});

chrome.tabs.onRemoved.addListener((tabId) => {
  quiet(chrome.storage.session.remove(mediaKey(tabId)));
});

chrome.tabs.onReplaced.addListener((addedTabId, removedTabId) => {
  quiet(chrome.storage.session.remove(mediaKey(removedTabId)));
});

// ---------------------------------------------------------------------------
// Sending things to the app
// ---------------------------------------------------------------------------

function isYouTube(u) {
  try {
    const url = new URL(u);
    return /(^|\.)youtube\.com$/i.test(url.hostname) || /(^|\.)youtu\.be$/i.test(url.hostname);
  } catch (_) {
    return false;
  }
}

function stripMedia(m) {
  return { url: m.url, type: m.type || '', size: m.size || 0, referer: m.referer || '', kind: m.kind || 'file' };
}

async function sendVideo(tabId, { extraSrc, frameUrl, onlyMedia } = {}) {
  const tab = await getTabSafe(tabId);
  const pageUrl = (tab && tab.url) || frameUrl || '';
  const title = (tab && tab.title) || '';
  let media;
  if (onlyMedia) {
    media = [stripMedia(onlyMedia)];
  } else {
    media = (await getTabMedia(tabId)).map(stripMedia);
    if (isHttpUrl(extraSrc)) {
      const norm = mediaKeyOf(extraSrc);
      if (!media.some((m) => mediaKeyOf(m.url) === norm)) {
        let path = '';
        try { path = new URL(extraSrc).pathname; } catch (_) { /* ignore */ }
        media.unshift({
          url: extraSrc,
          type: guessTypeFromExt(path),
          size: 0,
          referer: frameUrl || pageUrl,
          kind: kindFor('', path)
        });
      }
    }
    // YouTube (or nothing detected): let the app resolve the page itself (yt-dlp).
    if (isYouTube(pageUrl) || media.length === 0) {
      if (isYouTube(pageUrl)) media = media.filter((m) => !/googlevideo\.com/i.test(m.url));
      media.push({ url: pageUrl, type: 'text/html', size: 0, referer: pageUrl, kind: 'page' });
    }
  }
  return callApp('/video', { pageUrl, title, media });
}

async function sendAdd({ url, referer, pageUrl, fileName, fileSize }) {
  if (!isHttpUrl(url)) return { ok: false, error: 'unsupported_url' };
  return callApp('/add', {
    url,
    referer: referer || '',
    cookies: await getCookieHeader(url),
    userAgent: navigator.userAgent,
    fileName: fileName || fileNameFromUrl(url),
    pageUrl: pageUrl || referer || '',
    fileSize: fileSize || 0
  });
}

async function collectLinks(tabId) {
  try {
    const results = await chrome.scripting.executeScript({
      target: { tabId, allFrames: true },
      func: () => {
        const out = [];
        for (const a of document.querySelectorAll('a[href]')) {
          const href = a.href;
          if (!/^(https?|ftp):/i.test(href)) continue;
          const text = (a.innerText || a.textContent || a.title || '').replace(/\s+/g, ' ').trim().slice(0, 300);
          out.push({ url: href, text });
        }
        return out;
      }
    });
    const seen = new Set();
    const links = [];
    for (const r of results || []) {
      for (const l of (r && r.result) || []) {
        const key = l.url.split('#')[0];
        if (seen.has(key)) continue;
        seen.add(key);
        links.push(l);
      }
    }
    return links;
  } catch (_) {
    return [];
  }
}

async function sendLinks(tabId) {
  const tab = await getTabSafe(tabId);
  const links = await collectLinks(tabId);
  if (!links.length) return { ok: false, error: 'no_links' };
  return callApp('/links', { pageUrl: (tab && tab.url) || '', links });
}

const NOT_RUNNING_MSG = 'MRM Zone IDM is not running. Please open the app.';

function toastInTab(tabId, frameId, text) {
  if (typeof tabId !== 'number' || tabId < 0) return;
  const opts = typeof frameId === 'number' ? { frameId } : { frameId: 0 };
  quiet(chrome.tabs.sendMessage(tabId, { type: 'mrm-toast', text }, opts));
}

function resultMessage(result) {
  if (result.ok) return null;
  if (result.error === 'no_links') return 'No downloadable links found on this page.';
  if (result.error === 'unsupported_url') return 'This link cannot be downloaded by MRM Zone IDM.';
  return NOT_RUNNING_MSG;
}

// ---------------------------------------------------------------------------
// 4. Context menus
// ---------------------------------------------------------------------------

chrome.runtime.onInstalled.addListener(() => {
  quiet(async () => {
    await chrome.contextMenus.removeAll();
    chrome.contextMenus.create({
      id: 'mrm-add',
      title: 'Download with MRM Zone IDM',
      contexts: ['link', 'video', 'audio', 'image']
    }, () => void chrome.runtime.lastError);
    chrome.contextMenus.create({
      id: 'mrm-links',
      title: 'Download all links with MRM Zone IDM',
      contexts: ['page']
    }, () => void chrome.runtime.lastError);
    chrome.contextMenus.create({
      id: 'mrm-video',
      title: 'Download video with MRM Zone IDM',
      contexts: ['page']
    }, () => void chrome.runtime.lastError);
  });
  quiet(async () => {
    const cur = await chrome.storage.local.get(DEFAULT_SETTINGS);
    await chrome.storage.local.set({ ...DEFAULT_SETTINGS, ...cur });
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  quiet(async () => {
    const tabId = tab ? tab.id : -1;
    let result;
    if (info.menuItemId === 'mrm-add') {
      const url = info.linkUrl || info.srcUrl;
      result = await sendAdd({
        url,
        referer: info.frameUrl || info.pageUrl || '',
        pageUrl: info.pageUrl || (tab && tab.url) || ''
      });
    } else if (info.menuItemId === 'mrm-links') {
      result = await sendLinks(tabId);
    } else if (info.menuItemId === 'mrm-video') {
      result = await sendVideo(tabId, {});
    } else {
      return;
    }
    const msg = resultMessage(result);
    if (msg) toastInTab(tabId, info.frameId, msg);
  });
});

// ---------------------------------------------------------------------------
// Messages from the content script and popup
// ---------------------------------------------------------------------------

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || typeof msg.type !== 'string' || !msg.type.startsWith('mrm-')) return false;
  if (sender.id && sender.id !== chrome.runtime.id) return false;

  const respond = (p) => {
    Promise.resolve()
      .then(p)
      .then((r) => { try { sendResponse(r); } catch (_) { /* port closed */ } })
      .catch(() => { try { sendResponse({ ok: false, error: 'internal' }); } catch (_) { /* ignore */ } });
    return true; // async response
  };

  switch (msg.type) {
    case 'mrm-video-click': {
      const tabId = sender.tab ? sender.tab.id : -1;
      return respond(async () => {
        const r = await sendVideo(tabId, { extraSrc: msg.src, frameUrl: sender.url || msg.frameUrl });
        return { ok: r.ok, error: r.error, message: resultMessage(r) };
      });
    }
    case 'mrm-status':
      return respond(async () => {
        const found = await discoverApp(true);
        return found
          ? { connected: true, port: found.port, version: (found.info && found.info.version) || '' }
          : { connected: false };
      });
    case 'mrm-get-media':
      return respond(async () => ({ media: await getTabMedia(msg.tabId) }));
    case 'mrm-download-media':
      return respond(async () => {
        const item = msg.item || {};
        const tab = await getTabSafe(msg.tabId);
        let r;
        if (item.kind === 'file') {
          r = await sendAdd({
            url: item.url,
            referer: item.referer || (tab && tab.url) || '',
            pageUrl: (tab && tab.url) || '',
            fileSize: item.size || 0
          });
        } else {
          r = await sendVideo(msg.tabId, { onlyMedia: item });
        }
        return { ok: r.ok, error: r.error, message: resultMessage(r) };
      });
    case 'mrm-download-page-video':
      return respond(async () => {
        const r = await sendVideo(msg.tabId, {});
        return { ok: r.ok, error: r.error, message: resultMessage(r) };
      });
    case 'mrm-download-links':
      return respond(async () => {
        const r = await sendLinks(msg.tabId);
        return { ok: r.ok, error: r.error, message: resultMessage(r) };
      });
    default:
      return false;
  }
});

// Warm up the port cache when the browser starts.
chrome.runtime.onStartup.addListener(() => { quiet(discoverApp(true)); });
