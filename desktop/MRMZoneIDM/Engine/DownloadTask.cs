using System.Collections.Concurrent;
using System.Net;
using Microsoft.Win32.SafeHandles;

namespace MRMZoneIDM.Engine;

/// <summary>
/// IDM-style multi-connection download with dynamic segmentation: N parts on N connections;
/// when a connection finishes, the largest remaining part is split in half and a new connection takes it.
/// </summary>
public class DownloadTask
{
    public readonly DownloadItem Item;
    readonly Action<DownloadItem> onUpdate, onFinished, onSave;
    readonly CancellationTokenSource cts = new();
    readonly ConcurrentDictionary<Segment, Thread> workers = new();
    readonly List<Segment> segs = new();
    SafeFileHandle handle;
    volatile string fatal;
    public readonly SpeedLimiter Limiter;
    const long MinSplit = 256 * 1024;
    Thread thread;

    public bool Cancelled => cts.IsCancellationRequested;

    public DownloadTask(DownloadItem item, Action<DownloadItem> onUpdate, Action<DownloadItem> onFinished, Action<DownloadItem> onSave)
    {
        Item = item; this.onUpdate = onUpdate; this.onFinished = onFinished; this.onSave = onSave;
        Limiter = new SpeedLimiter(item.SpeedLimitKb * 1024L);
    }

    public void Start()
    {
        thread = new Thread(Run) { IsBackground = true, Name = "dl-" + Item.Id };
        thread.Start();
    }

    public void Cancel()
    {
        try { cts.Cancel(); } catch { }
        if (Item.IsYtdlp) Ytdlp.Kill(Item.Id);
    }

    public void Join(int ms) => thread?.Join(ms);

    void Run()
    {
        if (Item.IsYtdlp) { RunYtdlp(); return; }
        try
        {
            Item.LastTry = DateTime.Now; Item.Error = null;
            Item.Status = DStatus.Connecting; onUpdate(Item);
            Prepare();
            if (Cancelled) { Finish(DStatus.Paused); return; }
            Item.Status = DStatus.Downloading;
            lock (segs) Item.Segments = segs.Select(s => s.Copy()).ToList();
            onSave(Item); onUpdate(Item);
            Loop();
            CloseFile();
            if (fatal != null) { Item.Error = fatal; Finish(DStatus.Error); }
            else if (Cancelled) Finish(DStatus.Paused);
            else
            {
                Item.Downloaded = segs.Sum(s => s.Done);
                if (Item.TotalSize <= 0) Item.TotalSize = Item.Downloaded;
                Item.Status = DStatus.Merging; onUpdate(Item);
                var final = UniquePath(Item.FullPath);
                File.Move(Item.TempPath, final);
                Item.FileName = Path.GetFileName(final);
                Item.Segments = new();
                Item.FinishedAt = DateTime.Now;
                Finish(DStatus.Completed);
            }
        }
        catch (Exception e)
        {
            CloseFile();
            if (Cancelled) Finish(DStatus.Paused);
            else { Item.Error = e.Message; Finish(DStatus.Error); }
        }
    }

    public static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path); var b = Path.GetFileNameWithoutExtension(path); var ext = Path.GetExtension(path);
        for (int i = 1; ; i++) { var p = Path.Combine(dir, $"{b} ({i}){ext}"); if (!File.Exists(p)) return p; }
    }

    void Finish(DStatus s)
    {
        Item.Status = s; Item.Speed = 0; Item.ActiveConnections = 0;
        if (s != DStatus.Completed && !Item.IsYtdlp) lock (segs) Item.Segments = segs.Select(x => x.Copy()).ToList();
        onFinished(Item);
    }

    void Prepare()
    {
        Directory.CreateDirectory(Item.SaveDir);
        segs.Clear();
        segs.AddRange((Item.Segments ?? new()).Select(s => s.Copy()));
        bool have = segs.Count > 0 && File.Exists(Item.TempPath) && Item.Resumable && Item.TotalSize > 0;
        if (!have)
        {
            ProbeResult p = null; Exception last = null;
            for (int i = 0; i < 4 && p == null; i++)
            {
                if (Cancelled) return;
                try { p = Http.Probe(Item.Url, Item.UserAgent, Item.Referer, Item.Cookies, cts.Token).GetAwaiter().GetResult(); }
                catch (Exception e) { last = e; cts.Token.WaitHandle.WaitOne(1500 * (i + 1)); }
            }
            if (p == null) throw last ?? new IOException("Cannot connect");
            Item.TotalSize = p.Size; Item.Resumable = p.Resumable;
            segs.Clear();
            try { File.Delete(Item.TempPath); } catch { }
            if (p.Resumable && p.Size > 0)
            {
                long n = Math.Clamp(Item.Connections, 1, 32), part = p.Size / n;
                for (long i = 0; i < n; i++)
                {
                    long s = i * part, e = i == n - 1 ? p.Size - 1 : s + part - 1;
                    if (e >= s) segs.Add(new Segment { Start = s, End = e });
                }
            }
            else segs.Add(new Segment { Start = 0, End = p.Size > 0 ? p.Size - 1 : Segment.Unknown });
        }
        handle = File.OpenHandle(Item.TempPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite | FileShare.Delete);
        if (Item.TotalSize > 0 && RandomAccess.GetLength(handle) < Item.TotalSize) try { RandomAccess.SetLength(handle, Item.TotalSize); } catch { }
        Item.Downloaded = segs.Sum(s => s.Done);
        Item.LiveSegments = segs.Select(s => s.Copy()).ToList();
    }

    void CloseFile() { try { handle?.Dispose(); } catch { } handle = null; }

    void Loop()
    {
        long lastBytes = Item.Downloaded, lastTime = Environment.TickCount64, lastSave = lastTime;
        double ema = 0;
        while (!Cancelled && fatal == null)
        {
            lock (segs)
            {
                foreach (var s in segs.ToList())
                    if (!s.Finished && !workers.ContainsKey(s) && workers.Count < Math.Max(1, Item.Connections)) StartWorker(s);
                if (Item.Resumable && workers.Count < Item.Connections)
                {
                    var victim = workers.Keys.Where(x => x.Remaining > MinSplit * 2).OrderByDescending(x => x.Remaining).FirstOrDefault();
                    if (victim != null)
                    {
                        Segment ns = null;
                        lock (victim)
                        {
                            var rem = victim.Remaining;
                            if (rem > MinSplit * 2)
                            {
                                var mid = victim.Position + rem / 2;
                                ns = new Segment { Start = mid, End = victim.End };
                                victim.End = mid - 1;
                            }
                        }
                        if (ns != null) { segs.Add(ns); segs.Sort((a, b) => a.Start.CompareTo(b.Start)); StartWorker(ns); }
                    }
                }
                if (segs.All(s => s.Finished) && workers.IsEmpty) { Item.Downloaded = segs.Sum(s => s.Done); return; }
            }
            cts.Token.WaitHandle.WaitOne(400);
            var now = Environment.TickCount64;
            long bytes; lock (segs) bytes = segs.Sum(s => s.Done);
            var dt = Math.Max(1, now - lastTime);
            var inst = (bytes - lastBytes) * 1000.0 / dt;
            ema = ema == 0 ? inst : ema * 0.7 + inst * 0.3;
            lastBytes = bytes; lastTime = now;
            Item.Downloaded = bytes; Item.Speed = (long)ema; Item.ActiveConnections = workers.Count;
            lock (segs) Item.LiveSegments = segs.Select(s => s.Copy()).ToList();
            if (now - lastSave > 3000)
            {
                lock (segs) Item.Segments = segs.Select(s => s.Copy()).ToList();
                try { onSave(Item); } catch { }
                lastSave = now;
            }
            onUpdate(Item);
        }
        foreach (var w in workers.Values) w.Join(3000);
    }

    void StartWorker(Segment s)
    {
        var t = new Thread(() => Work(s)) { IsBackground = true, Name = $"dl-{Item.Id}-w" };
        workers[s] = t;
        t.Start();
    }

    void Work(Segment seg)
    {
        int attempts = 0;
        var buf = new byte[64 * 1024];
        try
        {
            while (!Cancelled && fatal == null && !seg.Finished)
            {
                try
                {
                    if (!Item.Resumable && seg.Done > 0) seg.Done = 0;
                    long from = Item.Resumable || seg.Position > 0 ? seg.Position : -1;
                    using var req = Http.Request(Item.Url, Item.UserAgent, Item.Referer, Item.Cookies, from, seg.End);
                    using var res = Http.Client.Send(req, HttpCompletionOption.ResponseHeadersRead, cts.Token);
                    int code = (int)res.StatusCode;
                    if (code == 416 && seg.Finished) break;
                    if (code >= 400)
                    {
                        attempts++;
                        if ((code >= 400 && code < 500 && code != 408 && code != 429) || attempts > App.Settings.Retries) { fatal = $"HTTP {code} {res.ReasonPhrase}"; break; }
                        cts.Token.WaitHandle.WaitOne(Math.Min(30000, 2000 * attempts)); continue;
                    }
                    if (res.StatusCode == HttpStatusCode.OK && seg.Position > 0 && Item.Resumable) { fatal = "Server does not support resume any more"; break; }
                    using var stream = res.Content.ReadAsStream(cts.Token);
                    using var readCts = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
                    bool stop = false;
                    while (!stop && !Cancelled)
                    {
                        int n;
                        readCts.CancelAfter(30000); // no data for 30s = stalled connection → retry
                        try { n = stream.ReadAsync(buf.AsMemory(), readCts.Token).AsTask().GetAwaiter().GetResult(); }
                        catch (OperationCanceledException) when (!Cancelled) { throw new IOException("Connection timed out"); }
                        if (n <= 0)
                        {
                            if (seg.End == Segment.Unknown) lock (seg) seg.End = seg.Position - 1;
                            else if (!seg.Finished) throw new IOException("Connection closed early");
                            break;
                        }
                        lock (seg)
                        {
                            long w = Math.Min(n, seg.Remaining);
                            if (w <= 0) stop = true;
                            else
                            {
                                RandomAccess.Write(handle, new ReadOnlySpan<byte>(buf, 0, (int)w), seg.Position);
                                seg.Done += w;
                                if (w < n) stop = true;
                            }
                        }
                        SpeedLimiter.Global.Acquire(n, cts.Token);
                        Limiter.Acquire(n, cts.Token);
                        attempts = 0;
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception e) when (e is IOException or HttpRequestException or TimeoutException)
                {
                    if (Cancelled) break;
                    attempts++;
                    if (attempts > App.Settings.Retries) { fatal = e.Message; break; }
                    cts.Token.WaitHandle.WaitOne(Math.Min(30000, 1500 * attempts));
                }
            }
        }
        catch (Exception e) { if (!Cancelled) fatal ??= e.Message; }
        finally { workers.TryRemove(seg, out _); }
    }

    // ---------------- yt-dlp (YouTube & video sites, HLS/DASH streams) ----------------
    void RunYtdlp()
    {
        try
        {
            Item.LastTry = DateTime.Now; Item.Error = null;
            Item.Status = DStatus.Connecting; onUpdate(Item);
            Tools.EnsureReady(null, cts.Token);
            if (Cancelled) { Finish(DStatus.Paused); return; }
            Item.Status = DStatus.Downloading; onUpdate(Item);
            var path = Ytdlp.Download(Item, cts.Token, () => onUpdate(Item));
            if (Cancelled) { Finish(DStatus.Paused); return; }
            Item.FileName = Path.GetFileName(path);
            Item.SaveDir = Path.GetDirectoryName(path);
            Item.TotalSize = new FileInfo(path).Length; Item.Downloaded = Item.TotalSize;
            Item.FinishedAt = DateTime.Now;
            Finish(DStatus.Completed);
        }
        catch (Exception e)
        {
            if (Cancelled) Finish(DStatus.Paused);
            else { Item.Error = e.Message; Finish(DStatus.Error); }
        }
    }
}
