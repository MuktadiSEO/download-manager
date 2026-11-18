using System.Diagnostics;
using System.IO.Compression;

namespace MRMZoneIDM.Engine;

/// <summary>Video engine: yt-dlp + ffmpeg + deno (JS runtime YouTube needs). Downloaded once to %LocalAppData%.</summary>
public static class Tools
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MRM Zone IDM", "tools");
    public static string YtDlp => Path.Combine(Dir, "yt-dlp.exe");
    public static string Ffmpeg => Path.Combine(Dir, "ffmpeg.exe");
    public static string Deno => Path.Combine(Dir, "deno.exe");

    const string YtDlpUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    const string FfmpegUrl = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
    const string DenoUrl = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip";

    static readonly SemaphoreSlim Gate = new(1, 1);

    public static bool Ready => File.Exists(YtDlp) && File.Exists(Ffmpeg) && File.Exists(Deno);

    /// <summary>Downloads whatever is missing. progress gets (text, 0..100 or -1).</summary>
    public static void EnsureReady(Action<string, int> progress, CancellationToken ct)
    {
        if (Ready) return;
        Gate.Wait(ct);
        try
        {
            Directory.CreateDirectory(Dir);
            if (!File.Exists(YtDlp)) Fetch(YtDlpUrl, YtDlp, "yt-dlp (video engine)", progress, ct);
            if (!File.Exists(Deno))
            {
                var zip = Path.Combine(Dir, "deno.zip");
                Fetch(DenoUrl, zip, "Deno (JavaScript engine for YouTube)", progress, ct);
                ExtractOne(zip, "deno.exe", Deno);
                File.Delete(zip);
            }
            if (!File.Exists(Ffmpeg))
            {
                var zip = Path.Combine(Dir, "ffmpeg.zip");
                Fetch(FfmpegUrl, zip, "FFmpeg (video/audio merger)", progress, ct);
                ExtractOne(zip, "ffmpeg.exe", Ffmpeg);
                ExtractOne(zip, "ffprobe.exe", Path.Combine(Dir, "ffprobe.exe"));
                File.Delete(zip);
            }
        }
        finally { Gate.Release(); }
    }

    static void ExtractOne(string zip, string name, string dest)
    {
        using var z = ZipFile.OpenRead(zip);
        var e = z.Entries.FirstOrDefault(x => x.Name.Equals(name, StringComparison.OrdinalIgnoreCase))
                ?? throw new IOException(name + " not found in " + Path.GetFileName(zip));
        e.ExtractToFile(dest + ".tmp", true);
        File.Move(dest + ".tmp", dest, true);
    }

    static void Fetch(string url, string dest, string label, Action<string, int> progress, CancellationToken ct)
    {
        progress?.Invoke($"Downloading {label}…", 0);
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", "MRMZoneIDM");
        using var res = Http.Client.Send(req, HttpCompletionOption.ResponseHeadersRead, ct);
        res.EnsureSuccessStatusCode();
        long total = res.Content.Headers.ContentLength ?? -1, done = 0;
        using (var src = res.Content.ReadAsStream(ct))
        using (var fs = File.Create(dest + ".part"))
        {
            var buf = new byte[256 * 1024]; int n; long lastTick = 0;
            while ((n = src.Read(buf, 0, buf.Length)) > 0)
            {
                ct.ThrowIfCancellationRequested();
                fs.Write(buf, 0, n); done += n;
                if (Environment.TickCount64 - lastTick > 300)
                {
                    lastTick = Environment.TickCount64;
                    progress?.Invoke($"Downloading {label}… {Fmt.Size(done)}" + (total > 0 ? $" / {Fmt.Size(total)}" : ""), total > 0 ? (int)(done * 100 / total) : -1);
                }
            }
        }
        File.Move(dest + ".part", dest, true);
    }

    public static string Version()
    {
        if (!File.Exists(YtDlp)) return "not installed";
        try { return Run(YtDlp, new[] { "--version" }, 20000).Trim(); } catch { return "?"; }
    }

    /// <summary>yt-dlp -U (self update). Returns the output.</summary>
    public static string Update()
    {
        if (!File.Exists(YtDlp)) { EnsureReady(null, CancellationToken.None); return "Installed " + Version(); }
        var o = Run(YtDlp, new[] { "-U" }, 120000);
        App.Settings.ToolsUpdateCheck = DateTime.Now; Store.Save(App.Settings);
        return o.Trim().Split('\n').LastOrDefault()?.Trim() ?? "done";
    }

    public static void BackgroundMaintenance()
    {
        Task.Run(() =>
        {
            try
            {
                if (!Ready) EnsureReady(null, CancellationToken.None);
                else if ((DateTime.Now - App.Settings.ToolsUpdateCheck).TotalHours > 24) Update();
            }
            catch { }
        });
    }

    public static string Run(string exe, IEnumerable<string> args, int timeoutMs)
    {
        var psi = new ProcessStartInfo(exe) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8, StandardErrorEncoding = System.Text.Encoding.UTF8 };
        foreach (var a in args) psi.ArgumentList.Add(a);
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        using var p = Process.Start(psi);
        var err = p.StandardError.ReadToEndAsync();
        var outp = p.StandardOutput.ReadToEnd();
        p.WaitForExit(timeoutMs);
        return outp + err.Result;
    }
}
