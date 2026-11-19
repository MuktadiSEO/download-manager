// MRM Zone IDM — runs in YouTube's own page (MAIN world).
// Reads the quality list the YouTube player already has, so the app can show all
// qualities instantly (like IDM) instead of waiting for yt-dlp to read the page.
(() => {
  if (window.__mrmYtMain) return;
  window.__mrmYtMain = true;

  const vidOf = (u) => { const m = String(u || '').match(/(?:[?&]v=|youtu\.be\/|\/shorts\/|\/live\/)([A-Za-z0-9_-]{11})/); return m ? m[1] : ''; };
  let lastSent = '';

  function playerResponse() {
    try { const p = document.getElementById('movie_player'); const r = p && p.getPlayerResponse && p.getPlayerResponse(); if (r && r.videoDetails) return r; } catch (_) { /* ignore */ }
    try { const r = window.ytInitialPlayerResponse; if (r && r.videoDetails) return r; } catch (_) { /* ignore */ }
    return null;
  }

  function convert(pr) {
    const vd = pr.videoDetails || {}, sd = pr.streamingData || {};
    const formats = [];
    for (const f of [...(sd.formats || []), ...(sd.adaptiveFormats || [])]) {
      if (!f || !f.itag || !f.mimeType) continue;
      const m = f.mimeType.match(/^(video|audio)\/(\w+);\s*codecs="([^"]+)"/);
      if (!m) continue;
      const isVideo = m[1] === 'video';
      const codecs = m[3].split(',').map((s) => s.trim());
      const ext = m[2] === 'mp4' ? (isVideo ? 'mp4' : 'm4a') : m[2];
      const kbps = (f.averageBitrate || f.bitrate || 0) / 1000;
      formats.push({
        format_id: String(f.itag), ext,
        vcodec: isVideo ? codecs[0] : 'none',
        acodec: isVideo ? (codecs[1] || 'none') : codecs[0],
        height: isVideo ? (f.height || 0) : 0, fps: f.fps || 0,
        tbr: kbps, abr: isVideo ? 0 : kbps,
        filesize: f.contentLength ? Number(f.contentLength) : 0
      });
    }
    const th = (vd.thumbnail && vd.thumbnail.thumbnails) || [];
    return {
      id: vd.videoId, title: vd.title || '', description: vd.shortDescription || '',
      uploader: vd.author || '', duration: Number(vd.lengthSeconds) || 0,
      thumbnail: vd.videoId ? `https://i.ytimg.com/vi/${vd.videoId}/hqdefault.jpg` : (th.length ? th[th.length - 1].url : ''),
      webpage_url: location.href, formats
    };
  }

  function check() {
    const want = vidOf(location.href);
    if (!want || want === lastSent) return;
    const pr = playerResponse();
    if (!pr || !pr.videoDetails || pr.videoDetails.videoId !== want || pr.videoDetails.isLive) return;
    const info = convert(pr);
    if (!info.formats.some((f) => f.vcodec !== 'none')) return;
    lastSent = want;
    window.postMessage({ __mrm: 'yt-info', pageUrl: location.href, info }, location.origin);
  }

  let tries = 0, timer = null;
  function burst() {
    tries = 0; clearInterval(timer);
    timer = setInterval(() => { check(); if (++tries > 40 || lastSent === vidOf(location.href)) clearInterval(timer); }, 250);
    check();
  }
  document.addEventListener('yt-navigate-finish', burst);
  document.addEventListener('yt-player-updated', check);
  window.addEventListener('message', (e) => { if (e.source === window && e.data && e.data.__mrm === 'yt-want') { lastSent = ''; burst(); } });
  burst();
})();
