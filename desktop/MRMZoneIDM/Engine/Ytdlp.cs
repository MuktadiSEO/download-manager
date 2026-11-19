using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MRMZoneIDM.Engine;

public class Quality
{
    public string Label, Detail, FormatSpec, Ext, Extra;
    public long Size = -1; public bool Approx, AudioOnly;
}

public class VideoInfo
{
    public string Title, Thumbnail, Uploader, PageUrl;
    public int Duration;
    public List<Quality> Qualities = new();
}

/// <summary>yt-dlp wrapper: YouTube + 1000 sites + raw HLS/DASH streams.</summary>
public static class Ytdlp
{
    static readonly ConcurrentDictionary<long, Process> Running = new();
    static readonly ConcurrentDictionary<string, (DateTime t, VideoInfo info)> Cache = new();

    static readonly string[] VideoHosts = {
        "youtube.com","youtu.be","youtube-nocookie.com","facebook.com","fb.watch","instagram.com","tiktok.com","twitter.com","x.com",
        "vimeo.com","dailymotion.com","dai.ly","twitch.tv","reddit.com","soundcloud.com","bilibili.com","likee.video","pinterest.com",
        "linkedin.com","threads.net","rumble.com","vk.com","ok.ru","streamable.com","bitchute.com" };

    static readonly Regex StatRe = new(@"^\s*(?:[\d.,]+\s*[KkMmBb]?\s*(?:views?|reactions?|comments?|shares?|likes?|plays?)\s*[·•|,\-–]?\s*)+\|?\s*", RegexOptions.IgnoreCase);
    static readonly Regex TailRe = new(@"(\s*(#[\p{L}\p{N}_]+|\|\s*(Facebook|Instagram|TikTok|YouTube)\s*$))+\s*$", RegexOptions.IgnoreCase);

    /// <summary>"60K views · 1K reactions | Real title #tag #tag | Facebook" → "Real title".</summary>
    public static string CleanTitle(string t)
    {
        if (string.IsNullOrWhiteSpace(t)) return "video";
        var r = StatRe.Replace(t.Trim(), "");
        r = TailRe.Replace(r, "");
        r = Regex.Replace(r, @"\s+", " ").Trim(' ', '|', '-', '·', '•', '_');
        return r.Length == 0 ? t.Trim() : r;
    }

    public static bool IsVideoSite(string url)
    {
        try { var h = new Uri(url).Host.ToLowerInvariant(); return VideoHosts.Any(v => h == v || h.EndsWith("." + v)); } catch { return false; }
    }

    static List<string> CommonArgs()
    {
        var a = new List<string> { "--no-warnings", "--encoding", "utf-8", "--ffmpeg-location", Tools.Dir };
        if (File.Exists(Tools.Deno)) { a.Add("--js-runtimes"); a.Add("deno:" + Tools.Deno); }
        return a;
    }

    static void AddHeaders(List<string> a, string referer, string cookies, string ua)
    {
        if (!string.IsNullOrEmpty(ua)) { a.Add("--user-agent"); a.Add(ua); }
        if (!string.IsNullOrEmpty(referer) && referer.StartsWith("http"))
        {
            a.Add("--referer"); a.Add(referer);
            try { var r = new Uri(referer); a.Add("--add-header"); a.Add($"Origin:{r.Scheme}://{r.Host}"); } catch { }
        }
        if (!string.IsNullOrEmpty(cookies)) { a.Add("--add-header"); a.Add("Cookie:" + cookies); }
    }

    static string Key(string url)
    {
        var m = Regex.Match(url ?? "", @"(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
        return m.Success ? "yt:" + m.Groups[1].Value : url;
    }

    static readonly ConcurrentDictionary<string, Task<VideoInfo>> Inflight = new();
    static readonly SemaphoreSlim FetchSlots = new(3, 3);

    public static bool IsCached(string url) => Cache.TryGetValue(Key(url), out var c) && (DateTime.Now - c.t).TotalMinutes < 30;

    /// <summary>Start reading the qualities in the background (browser extension calls this as soon as a video page opens).</summary>
    public static void Prefetch(string url, string referer = null, string cookies = null, string ua = null)
    {
        if (string.IsNullOrEmpty(url) || IsCached(url)) return;
        _ = Start(url, referer, cookies, ua);
    }

    /// <summary>Cached result, or the running lookup for the same video, or a new lookup.</summary>
    public static VideoInfo Fetch(string url, string referer = null, string cookies = null, string ua = null, CancellationToken ct = default)
    {
        var key = Key(url);
        if (Cache.TryGetValue(key, out var c) && (DateTime.Now - c.t).TotalMinutes < 30) return c.info;
        return Start(url, referer, cookies, ua).WaitAsync(ct).GetAwaiter().GetResult();
    }

    static Task<VideoInfo> Start(string url, string referer, string cookies, string ua)
    {
        var key = Key(url);
        return Inflight.GetOrAdd(key, _ =>
        {
            var t = Task.Run(async () =>
            {
                await FetchSlots.WaitAsync();
                try { return FetchNow(url, referer, cookies, ua); }
                finally { FetchSlots.Release(); }
            });
            t.ContinueWith(_ => Inflight.TryRemove(key, out var _x));
            return t;
        });
    }

    static VideoInfo FetchNow(string url, string referer, string cookies, string ua)
    {
        var key = Key(url);
        if (Cache.TryGetValue(key, out var c) && (DateTime.Now - c.t).TotalMinutes < 30) return c.info;
        var ct = CancellationToken.None;
        Tools.EnsureReady(null, ct);
        var a = CommonArgs();
        a.AddRange(new[] { "-J", "--no-playlist" });
        AddHeaders(a, referer, cookies, ua);
        a.Add(url);
        var psi = new ProcessStartInfo(Tools.YtDlp) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8, StandardErrorEncoding = System.Text.Encoding.UTF8 };
        foreach (var x in a) psi.ArgumentList.Add(x);
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        using var p = Process.Start(psi);
        using var reg = ct.Register(() => { try { p.Kill(true); } catch { } });
        var errTask = p.StandardError.ReadToEndAsync();
        var json = p.StandardOutput.ReadToEnd();
        p.WaitForExit();
        var err = errTask.Result;
        if (p.ExitCode != 0 || string.IsNullOrWhiteSpace(json))
            throw new Exception(LastError(err) ?? "yt-dlp could not read this page");
        var info = Parse(json, url);
        Cache[key] = (DateTime.Now, info);
        return info;
    }

    public static string LastError(string err) =>
        err?.Split('\n').Select(l => l.Trim()).LastOrDefault(l => l.StartsWith("ERROR"))?.Replace("ERROR:", "").Trim()
        ?? err?.Split('\n').Select(l => l.Trim()).LastOrDefault(l => l.Length > 0);

    record F(string Id, string Ext, string VCodec, string ACodec, int Height, int Fps, double Tbr, double Abr, long Size, bool Approx)
    {
        public bool IsVideo => !string.IsNullOrEmpty(VCodec) && VCodec != "none" && Height > 0;
        public bool IsAudioOnly => (string.IsNullOrEmpty(VCodec) || VCodec == "none") && !string.IsNullOrEmpty(ACodec) && ACodec != "none";
        public bool HasAudio => !string.IsNullOrEmpty(ACodec) && ACodec != "none";
    }

    static string S(JsonElement e, string n) => e.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    static double D(JsonElement e, string n) => e.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;

    static VideoInfo Parse(string json, string url)
    {
        using var doc = JsonDocument.Parse(json);
        var r = doc.RootElement;
        int dur = (int)D(r, "duration");
        var list = new List<F>();
        if (r.TryGetProperty("formats", out var fs) && fs.ValueKind == JsonValueKind.Array)
            foreach (var f in fs.EnumerateArray())
            {
                var id = S(f, "format_id"); var ext = S(f, "ext");
                if (id == null || id.StartsWith("sb") || ext == "mhtml") continue;
                double tbr = D(f, "tbr"), abr = D(f, "abr");
                long size = (long)D(f, "filesize"); bool approx = false;
                if (size <= 0) { size = (long)D(f, "filesize_approx"); approx = size > 0; }
                if (size <= 0 && dur > 0 && (tbr > 0 || abr > 0)) { size = (long)((tbr > 0 ? tbr : abr) * 1000 / 8 * dur); approx = true; }
                if (size <= 0) { size = -1; approx = false; }
                list.Add(new F(id, ext, S(f, "vcodec"), S(f, "acodec"), (int)D(f, "height"), (int)D(f, "fps"), tbr, abr, size, approx));
            }
        var audios = list.Where(x => x.IsAudioOnly).ToList();
        var bestM4a = audios.Where(x => x.Ext == "m4a").OrderByDescending(x => Math.Max(x.Abr, x.Tbr)).FirstOrDefault();
        var bestWebmA = audios.Where(x => x.Ext == "webm").OrderByDescending(x => Math.Max(x.Abr, x.Tbr)).FirstOrDefault();
        var bestAudio = audios.OrderByDescending(x => Math.Max(x.Abr, x.Tbr)).FirstOrDefault();
        var info = new VideoInfo { Title = CleanTitle(S(r, "title") ?? S(r, "fulltitle") ?? "video"), Thumbnail = S(r, "thumbnail"), Uploader = S(r, "uploader"), Duration = dur, PageUrl = S(r, "webpage_url") ?? url };

        foreach (var g in list.Where(x => x.IsVideo).GroupBy(x => x.Height).OrderByDescending(g => g.Key))
        {
            int h = g.Key;
            int Score(F f)
            {
                var v = f.VCodec ?? "";
                int codec = h <= 1080
                    ? (v.StartsWith("avc") ? 3 : v.StartsWith("vp9") || v.StartsWith("vp09") ? 2 : 1)
                    : (v.StartsWith("vp9") || v.StartsWith("vp09") ? 3 : v.StartsWith("av01") ? 2 : 1);
                return codec * 1_000_000 + f.Fps * 10_000 + (int)f.Tbr;
            }
            int maxFps = g.Max(x => x.Fps);
            var best = g.Where(x => x.Fps == maxFps || maxFps <= 30).OrderByDescending(Score).First();
            string fpsTxt = best.Fps > 30 ? best.Fps.ToString() : "";
            string name = h >= 4320 ? "8K" : h >= 2160 ? "4K" : h >= 1440 ? "2K" : h >= 1080 ? "Full HD" : h >= 720 ? "HD" : "SD";
            var vc = best.VCodec ?? "";
            string codecName = vc.StartsWith("avc") ? "H.264" : vc.StartsWith("vp") ? "VP9" : vc.StartsWith("av01") ? "AV1" : vc.Split('.')[0].ToUpperInvariant();
            if (best.HasAudio)
                info.Qualities.Add(new Quality { Label = $"{h}p{fpsTxt}", Detail = $"{best.Ext?.ToUpperInvariant()} • {name} • {codecName}", FormatSpec = best.Id, Ext = best.Ext ?? "mp4", Size = best.Size, Approx = best.Approx });
            else
            {
                (F aud, string merge) = best.Ext == "mp4" && bestM4a != null ? (bestM4a, "mp4") : best.Ext == "webm" && bestWebmA != null ? (bestWebmA, "webm") : (bestAudio, "mp4");
                var spec = aud != null ? $"{best.Id}+{aud.Id}" : $"{best.Id}+bestaudio/best";
                long size = best.Size > 0 ? best.Size + Math.Max(0, aud?.Size ?? 0) : -1;
                info.Qualities.Add(new Quality { Label = $"{h}p{fpsTxt}", Detail = $"{merge.ToUpperInvariant()} • {name} • {codecName}", FormatSpec = spec, Ext = merge, Extra = merge, Size = size, Approx = best.Approx || (aud?.Approx ?? false) });
            }
        }
        if (bestM4a != null) info.Qualities.Add(new Quality { Label = "Audio", Detail = $"M4A • {(int)Math.Max(bestM4a.Abr, bestM4a.Tbr)} kbps", FormatSpec = bestM4a.Id, Ext = "m4a", Size = bestM4a.Size, Approx = bestM4a.Approx, AudioOnly = true });
        if (bestAudio != null) info.Qualities.Add(new Quality { Label = "MP3", Detail = "MP3 • converted from best audio", FormatSpec = bestAudio.Id, Ext = "mp3", Extra = "mp3", Size = bestAudio.Size, Approx = true, AudioOnly = true });
        if (info.Qualities.Count == 0)
        {
            info.Qualities.Add(new Quality { Label = "Best", Detail = "Best available quality", FormatSpec = "bv*+ba/b", Ext = "mp4", Extra = "mp4" });
            info.Qualities.Add(new Quality { Label = "MP3", Detail = "Audio only (MP3)", FormatSpec = "ba/b", Ext = "mp3", Extra = "mp3", AudioOnly = true });
        }
        return info;
    }

    public static void Kill(long id)
    {
        if (Running.TryRemove(id, out var p)) try { p.Kill(true); } catch { }
    }

    static long Num(string s) => long.TryParse(s, out var v) ? v : double.TryParse(s, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d) ? (long)d : -1;

    /// <summary>Runs the download; returns the final file path.</summary>
    public static string Download(DownloadItem item, CancellationToken ct, Action onProgress)
    {
        Directory.CreateDirectory(item.SaveDir);
        var baseName = Path.GetFileNameWithoutExtension(item.FileName);
        var a = CommonArgs();
        a.AddRange(new[] {
            "--no-playlist", "-f", item.FormatSpec ?? "bv*+ba/b",
            "-o", Path.Combine(item.SaveDir, baseName + ".%(ext)s"),
            "--no-mtime", "--continue", "--newline", "--progress", "--no-simulate",
            "--http-chunk-size", "10M", "-N", Math.Clamp(item.Connections, 1, 16).ToString(),
            "--retries", Math.Max(1, App.Settings.Retries).ToString(), "--fragment-retries", Math.Max(1, App.Settings.Retries).ToString(),
            "--progress-template", "download:MRMP %(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s %(info.format_id)s",
            "--print", "after_move:MRMFILE %(filepath)s",
        });
        switch (item.Extra)
        {
            case "mp3": a.AddRange(new[] { "-x", "--audio-format", "mp3", "--audio-quality", "0" }); break;
            case "mp4": case "webm": case "mkv": a.AddRange(new[] { "--merge-output-format", item.Extra }); break;
        }
        var limits = new[] { item.SpeedLimitKb, App.Settings.SpeedLimitKb }.Where(x => x > 0).ToArray();
        if (limits.Length > 0) { a.Add("-r"); a.Add(limits.Min() + "K"); }
        AddHeaders(a, item.Referer, item.Cookies, item.UserAgent);
        a.Add(item.Url);

        var psi = new ProcessStartInfo(Tools.YtDlp) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8, StandardErrorEncoding = System.Text.Encoding.UTF8 };
        foreach (var x in a) psi.ArgumentList.Add(x);
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        using var p = Process.Start(psi);
        Running[item.Id] = p;
        var err = new System.Text.StringBuilder();
        p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (err) err.AppendLine(e.Data); };
        p.BeginErrorReadLine();
        string finalPath = null, lastFmt = null; long phaseBase = 0, phaseSize = 0, lastEmit = 0;
        string line;
        while ((line = p.StandardOutput.ReadLine()) != null)
        {
            if (ct.IsCancellationRequested) break;
            if (line.StartsWith("MRMFILE ")) { finalPath = line[8..].Trim(); continue; }
            if (line.StartsWith("MRMP "))
            {
                var t = line.Split(' ');
                if (t.Length >= 6)
                {
                    if (t[5] != lastFmt) { if (lastFmt != null) phaseBase += phaseSize; lastFmt = t[5]; phaseSize = 0; }
                    long done = Num(t[1]), total = Num(t[2]); if (total <= 0) total = Num(t[3]);
                    if (total > 0) phaseSize = total;
                    long sp = Num(t[4]);
                    if (done >= 0) item.Downloaded = item.TotalSize > 0 ? Math.Min(phaseBase + done, item.TotalSize) : phaseBase + done;
                    if (item.TotalSize <= 0 && total > 0 && lastFmt != null && !lastFmt.Contains('+')) { }
                    if (sp >= 0) item.Speed = sp;
                    item.ActiveConnections = 1;
                }
            }
            else if (line.StartsWith("[Merger]") || line.StartsWith("[ExtractAudio]") || line.StartsWith("[FixupM") || line.StartsWith("[VideoConvertor]"))
            { item.Status = DStatus.Merging; item.Speed = 0; }
            if (Environment.TickCount64 - lastEmit > 400) { lastEmit = Environment.TickCount64; onProgress(); }
        }
        p.WaitForExit();
        Running.TryRemove(item.Id, out _);
        if (ct.IsCancellationRequested) throw new OperationCanceledException();
        if (p.ExitCode != 0) { string e; lock (err) e = err.ToString(); throw new Exception(LastError(e) ?? "yt-dlp failed"); }
        if (finalPath == null || !File.Exists(finalPath))
            finalPath = Directory.GetFiles(item.SaveDir, baseName + ".*")
                .Where(f => !f.EndsWith(".part") && !f.EndsWith(".ytdl") && !f.Contains(".part-") && !f.EndsWith(".temp"))
                .OrderByDescending(f => new FileInfo(f).Length).FirstOrDefault();
        return finalPath ?? throw new IOException("Downloaded file not found");
    }
}
