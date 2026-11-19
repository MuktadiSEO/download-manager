using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace MRMZoneIDM.Engine;

/// <summary>
/// Tiny HTTP server on 127.0.0.1:47321..47325 for the browser extension:
/// GET /ping, POST /add, POST /video, POST /links, GET /show.
/// </summary>
public static class LocalServer
{
    public static int Port { get; private set; }
    public static DateTime LastExtensionContact { get; private set; }
    public static bool ExtensionConnected => (DateTime.Now - LastExtensionContact).TotalMinutes < 10;
    public static event Action<JsonElement> OnAdd, OnVideo, OnLinks;
    public static event Action OnShow;
    static TcpListener listener;

    public static void Start()
    {
        for (int p = 47321; p <= 47325; p++)
        {
            try { listener = new TcpListener(IPAddress.Loopback, p); listener.Start(); Port = p; break; }
            catch { listener = null; }
        }
        if (listener == null) return;
        Task.Run(AcceptLoop);
    }

    static async Task AcceptLoop()
    {
        while (true)
        {
            TcpClient c;
            try { c = await listener.AcceptTcpClientAsync(); } catch { return; }
            _ = Task.Run(() => Handle(c));
        }
    }

    static async Task Handle(TcpClient client)
    {
        using var c = client;
        try
        {
            c.ReceiveTimeout = 10000;
            var stream = c.GetStream();
            var buf = new byte[1024 * 1024];
            int total = 0, headerEnd = -1;
            while (headerEnd < 0 && total < buf.Length)
            {
                int n = await stream.ReadAsync(buf.AsMemory(total, buf.Length - total));
                if (n <= 0) return;
                total += n;
                headerEnd = IndexOf(buf, total, "\r\n\r\n"u8);
            }
            if (headerEnd < 0) return;
            var head = Encoding.ASCII.GetString(buf, 0, headerEnd).Split("\r\n");
            var parts = head[0].Split(' ');
            if (parts.Length < 2) return;
            string method = parts[0], path = parts[1].Split('?')[0];
            int len = 0;
            foreach (var h in head.Skip(1))
                if (h.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) int.TryParse(h[15..].Trim(), out len);
            int bodyStart = headerEnd + 4;
            len = Math.Min(len, buf.Length - bodyStart);
            while (total - bodyStart < len)
            {
                int n = await stream.ReadAsync(buf.AsMemory(total, buf.Length - total));
                if (n <= 0) break;
                total += n;
            }
            var body = Encoding.UTF8.GetString(buf, bodyStart, Math.Max(0, Math.Min(len, total - bodyStart)));

            string resp = "{\"ok\":true}"; int code = 200;
            if (method == "OPTIONS") resp = "";
            else
            {
                LastExtensionContact = DateTime.Now;
                switch (path)
                {
                    case "/ping": resp = JsonSerializer.Serialize(new { app = "MRM Zone IDM", version = App.Version, extVersion = BundledExtVersion() }); break;
                    case "/show": OnShow?.Invoke(); break;
                    case "/add": Fire(OnAdd, body); break;
                    case "/video": Fire(OnVideo, body); break;
                    case "/links": Fire(OnLinks, body); break;
                    case "/prefetch": Prefetch(body); break;
                    default: code = 404; resp = "{\"ok\":false}"; break;
                }
            }
            var bytes = Encoding.UTF8.GetBytes(resp);
            var hdr = $"HTTP/1.1 {code} OK\r\nContent-Type: application/json\r\nContent-Length: {bytes.Length}\r\n" +
                      "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n" +
                      "Access-Control-Allow-Private-Network: true\r\nConnection: close\r\n\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(hdr));
            await stream.WriteAsync(bytes);
        }
        catch { }
    }

    static void Fire(Action<JsonElement> ev, string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
            ev?.Invoke(doc.RootElement.Clone());
        }
        catch { }
    }

    static string extVer;
    /// <summary>Version of the extension shipped in the install folder (the one Chrome loads unpacked).</summary>
    static string BundledExtVersion()
    {
        if (extVer != null) return extVer;
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "extension", "manifest.json")));
            extVer = doc.RootElement.GetProperty("version").GetString();
        }
        catch { extVer = ""; }
        return extVer;
    }

    static void Prefetch(string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
            var r = doc.RootElement;
            if (r.ValueKind == JsonValueKind.Object && r.TryGetProperty("cookieJar", out var jar)) CookieStore.Save(jar);
            string S(string n) => r.ValueKind == JsonValueKind.Object && r.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
            var url = S("url") ?? S("pageUrl");
            if (string.IsNullOrEmpty(url) || !url.StartsWith("http")) return;
            // YouTube: the player's own quality list, sent by the extension → instant, no yt-dlp needed to show it
            if (r.TryGetProperty("ytInfo", out var yi) && yi.ValueKind == JsonValueKind.Object) { Ytdlp.Seed(url, yi.GetRawText()); return; }
            // video sites: by page URL (no headers); raw streams: with the page's referer + cookies
            if (S("url") == null) Ytdlp.Prefetch(url);
            else Ytdlp.Prefetch(url, S("referer"), S("cookies"), null);
        }
        catch { }
    }

    static int IndexOf(byte[] b, int len, ReadOnlySpan<byte> pat)
    {
        var i = b.AsSpan(0, len).IndexOf(pat);
        return i;
    }

    /// <summary>Used by a second app instance to hand its URL to the running one.</summary>
    public static bool SendToRunning(string path, object payload)
    {
        for (int p = 47321; p <= 47325; p++)
        {
            try
            {
                using var hc = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
                var ping = hc.GetStringAsync($"http://127.0.0.1:{p}/ping").Result;
                if (!ping.Contains("MRM Zone IDM")) continue;
                if (payload == null) hc.GetAsync($"http://127.0.0.1:{p}{path}").Wait();
                else hc.PostAsync($"http://127.0.0.1:{p}{path}", new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")).Wait();
                return true;
            }
            catch { }
        }
        return false;
    }
}
