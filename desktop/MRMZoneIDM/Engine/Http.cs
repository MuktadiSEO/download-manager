using System.Net;
using System.Net.Http.Headers;
using System.Text.RegularExpressions;
using System.Web;

namespace MRMZoneIDM.Engine;

public record ProbeResult(string FinalUrl, long Size, bool Resumable, string FileName, string ContentType);

public static class Http
{
    public static readonly HttpClient Client = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = true,
        MaxAutomaticRedirections = 15,
        AutomaticDecompression = DecompressionMethods.None,
        MaxConnectionsPerServer = 256,
        UseCookies = false,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        ConnectTimeout = TimeSpan.FromSeconds(20),
    })
    { Timeout = Timeout.InfiniteTimeSpan };

    public static HttpRequestMessage Request(string url, string ua, string referer, string cookies, long from = -1, long to = -1, HttpMethod method = null)
    {
        var r = new HttpRequestMessage(method ?? HttpMethod.Get, url);
        r.Headers.TryAddWithoutValidation("User-Agent", string.IsNullOrEmpty(ua) ? App.Settings.UserAgent : ua);
        r.Headers.TryAddWithoutValidation("Accept", "*/*");
        r.Headers.TryAddWithoutValidation("Accept-Encoding", "identity");
        if (!string.IsNullOrEmpty(referer)) r.Headers.TryAddWithoutValidation("Referer", referer);
        if (!string.IsNullOrEmpty(cookies)) r.Headers.TryAddWithoutValidation("Cookie", cookies);
        if (from >= 0) r.Headers.Range = new RangeHeaderValue(from, to >= 0 && to != Segment.Unknown ? to : null);
        return r;
    }

    public static async Task<ProbeResult> Probe(string url, string ua, string referer, string cookies, CancellationToken ct = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(30));
        using var req = Request(url, ua, referer, cookies, 0, 0);
        using var res = await Client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, cts.Token);
        if ((int)res.StatusCode >= 400) throw new HttpRequestException($"HTTP {(int)res.StatusCode} {res.ReasonPhrase}");
        var final = res.RequestMessage?.RequestUri?.ToString() ?? url;
        var type = res.Content.Headers.ContentType?.MediaType;
        long size = -1; bool resumable = false;
        if (res.StatusCode == HttpStatusCode.PartialContent)
        {
            size = res.Content.Headers.ContentRange?.Length ?? -1;
            resumable = size > 0;
        }
        else
        {
            size = res.Content.Headers.ContentLength ?? -1;
            resumable = res.Headers.AcceptRanges.Contains("bytes") && size > 0;
        }
        var cd = res.Content.Headers.ContentDisposition;
        var name = FileNameFrom(cd?.FileNameStar ?? cd?.FileName, final, type);
        return new ProbeResult(final, size, resumable, name, type);
    }

    static readonly Dictionary<string, string> MimeExt = new()
    {
        ["video/mp4"] = "mp4", ["video/webm"] = "webm", ["audio/mpeg"] = "mp3", ["audio/mp4"] = "m4a", ["application/pdf"] = "pdf",
        ["application/zip"] = "zip", ["application/x-rar-compressed"] = "rar", ["application/vnd.android.package-archive"] = "apk",
        ["image/jpeg"] = "jpg", ["image/png"] = "png", ["application/x-msdownload"] = "exe", ["video/x-matroska"] = "mkv",
    };

    public static string FileNameFrom(string disposition, string url, string mime)
    {
        string name = disposition?.Trim('"', ' ');
        if (string.IsNullOrWhiteSpace(name))
        {
            try
            {
                var u = new Uri(url);
                name = Uri.UnescapeDataString(u.AbsolutePath.Split('/').LastOrDefault() ?? "");
                if (string.IsNullOrWhiteSpace(name) || !name.Contains('.'))
                {
                    var q = HttpUtility.ParseQueryString(u.Query);
                    foreach (var k in new[] { "filename", "file", "name", "title" })
                        if (!string.IsNullOrWhiteSpace(q[k])) { name = q[k]; break; }
                }
            }
            catch { }
        }
        if (string.IsNullOrWhiteSpace(name)) name = "download";
        if (!Path.HasExtension(name) && mime != null && MimeExt.TryGetValue(mime, out var ext)) name += "." + ext;
        return Sanitize(name);
    }

    public static string Sanitize(string n)
    {
        n = Regex.Replace(n ?? "", @"[\\/:*?""<>|\r\n\t]", "_").Trim().Trim('.');
        if (n.Length == 0) n = "download";
        if (n.Length > 180) { var ext = Path.GetExtension(n); n = n[..(180 - ext.Length)] + ext; }
        return n;
    }

    public static readonly Regex UrlRe = new(@"https?://[^\s<>""']+", RegexOptions.IgnoreCase);
    public static string ExtractUrl(string text) => text == null ? null : UrlRe.Match(text) is { Success: true } m ? m.Value.TrimEnd('.', ',', ')', ']') : null;
}

/// <summary>Token bucket. limit &lt;= 0 = unlimited.</summary>
public class SpeedLimiter
{
    public long LimitBytesPerSec;
    long windowStart = Environment.TickCount64, inWindow;
    readonly object l = new();
    public static readonly SpeedLimiter Global = new();
    public SpeedLimiter(long limit = 0) { LimitBytesPerSec = limit; }

    public void Acquire(int n, CancellationToken ct)
    {
        var limit = LimitBytesPerSec;
        if (limit <= 0) return;
        long sleep;
        lock (l)
        {
            var now = Environment.TickCount64;
            if (now - windowStart > 1000) { windowStart = now; inWindow = 0; }
            inWindow += n;
            sleep = inWindow * 1000 / limit - (now - windowStart);
        }
        if (sleep > 0) ct.WaitHandle.WaitOne((int)Math.Min(sleep, 2000));
    }
}
