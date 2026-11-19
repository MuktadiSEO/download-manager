using System.Text;
using System.Text.Json;

namespace MRMZoneIDM.Engine;

/// <summary>
/// Login cookies for video sites, handed over by the browser extension (Chrome encrypts its own
/// cookie store, so yt-dlp cannot read it). Stored per site in %LocalAppData%\MRM Zone IDM\cookies
/// as Netscape cookies.txt and passed to yt-dlp with --cookies. Fixes YouTube's
/// "Sign in to confirm you're not a bot" and private Facebook/Instagram videos.
/// </summary>
public static class CookieStore
{
    static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MRM Zone IDM", "cookies");
    static readonly string[] Sites = { "youtube.com", "facebook.com", "instagram.com", "tiktok.com", "x.com", "twitter.com", "vimeo.com", "dailymotion.com" };
    static readonly object Lock = new();

    public static string SiteFor(string url)
    {
        string host;
        try { host = new Uri(url).Host.ToLowerInvariant(); } catch { return null; }
        if (host == "youtu.be") return "youtube.com";
        if (host == "fb.watch") return "facebook.com";
        return Sites.FirstOrDefault(d => host == d || host.EndsWith("." + d));
    }

    /// <summary>Saves the "cookieJar" object sent by the extension: { site, cookies:[{domain,path,secure,hostOnly,expires,name,value}] }.</summary>
    public static void Save(JsonElement jar)
    {
        try
        {
            if (jar.ValueKind != JsonValueKind.Object) return;
            var site = jar.TryGetProperty("site", out var s) && s.ValueKind == JsonValueKind.String ? s.GetString() : null;
            if (site == null || !Sites.Contains(site)) return;
            if (!jar.TryGetProperty("cookies", out var arr) || arr.ValueKind != JsonValueKind.Array) return;
            var sb = new StringBuilder("# Netscape HTTP Cookie File\n# Saved by MRM Zone IDM from your browser\n\n");
            int n = 0;
            foreach (var c in arr.EnumerateArray())
            {
                string Str(string k) => c.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : "";
                bool Bool(string k) => c.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;
                long exp = c.TryGetProperty("expires", out var e) && e.ValueKind == JsonValueKind.Number ? (long)e.GetDouble() : 0;
                var domain = Str("domain"); var name = Str("name");
                if (domain.Length == 0 || name.Length == 0) continue;
                if (!Bool("hostOnly") && !domain.StartsWith('.')) domain = "." + domain;
                var value = Str("value").Replace("\t", " ").Replace("\n", " ");
                sb.Append(domain).Append('\t').Append(domain.StartsWith('.') ? "TRUE" : "FALSE").Append('\t')
                  .Append(string.IsNullOrEmpty(Str("path")) ? "/" : Str("path")).Append('\t').Append(Bool("secure") ? "TRUE" : "FALSE").Append('\t')
                  .Append(exp).Append('\t').Append(name).Append('\t').Append(value).Append('\n');
                n++;
            }
            if (n == 0) return;
            lock (Lock)
            {
                Directory.CreateDirectory(Dir);
                var path = Path.Combine(Dir, site + ".txt");
                File.WriteAllText(path + ".tmp", sb.ToString());
                File.Move(path + ".tmp", path, true);
            }
        }
        catch { }
    }

    /// <summary>Adds --cookies with a private copy (yt-dlp rewrites the file it is given). Returns the copy to delete afterwards.</summary>
    public static string AddArgs(List<string> args, string url)
    {
        var site = SiteFor(url);
        if (site == null) return null;
        var src = Path.Combine(Dir, site + ".txt");
        if (!File.Exists(src)) return null;
        try
        {
            var copy = Path.Combine(Path.GetTempPath(), $"mrm-cookies-{Guid.NewGuid():N}.txt");
            lock (Lock) File.Copy(src, copy, true);
            args.Add("--cookies"); args.Add(copy);
            return copy;
        }
        catch { return null; }
    }

    public static void Cleanup(string copy) { if (copy != null) try { File.Delete(copy); } catch { } }
}
