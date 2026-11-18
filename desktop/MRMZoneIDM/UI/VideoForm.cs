using MRMZoneIDM.Engine;

namespace MRMZoneIDM.UI;

public class MediaRef { public string Url, Type, Kind, Referer, Cookies; public long Size = -1; }

/// <summary>"Download this video": title, thumbnail and every quality (YouTube & 1000+ sites, HLS/DASH streams, files).</summary>
public class VideoForm : Form
{
    readonly FlowLayoutPanel rows = new() { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(16, 8, 16, 8), BackColor = Color.White };
    readonly Label status = new() { Dock = DockStyle.Top, Height = 30, Padding = new Padding(16, 8, 16, 0), ForeColor = Theme.Gray };
    readonly ProgressBar bar = new() { Dock = DockStyle.Top, Height = 4, Style = ProgressBarStyle.Marquee };
    readonly CheckBox queue = new() { Text = "Add to queue (download later)", AutoSize = true };
    readonly string pageUrl, title, referer, cookies, ua;
    readonly List<MediaRef> media;
    readonly bool fallbackLinks;
    readonly CancellationTokenSource cts = new();

    public static void Open(string pageUrl, string title, string referer, string cookies, string ua, List<MediaRef> media, bool fallbackLinks = false)
    {
        var f = new VideoForm(pageUrl, title, referer, cookies, ua, media ?? new(), fallbackLinks);
        f.Show(); f.Activate();
    }

    VideoForm(string pageUrl, string title, string referer, string cookies, string ua, List<MediaRef> media, bool fallbackLinks)
    {
        this.pageUrl = pageUrl; this.title = title; this.referer = referer; this.cookies = cookies; this.ua = ua; this.media = media; this.fallbackLinks = fallbackLinks;
        Text = "Download video — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; BackColor = Color.White;
        Size = new Size(620, 720); StartPosition = FormStartPosition.CenterScreen; TopMost = true;
        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 52, Padding = new Padding(16, 10, 16, 10), BackColor = Color.FromArgb(0xF6, 0xF8, 0xFB) };
        queue.Location = new Point(16, 16); bottom.Controls.Add(queue);
        var close = Theme.Btn("Close", false, 90); close.Location = new Point(500, 9); close.Anchor = AnchorStyles.Right | AnchorStyles.Top; close.Click += (_, _) => Close();
        bottom.Controls.Add(close);
        Controls.Add(rows); Controls.Add(bar); Controls.Add(status);
        Controls.Add(Theme.Header("Download Video", "YouTube • Facebook • Instagram • TikTok • any site"));
        Controls.Add(bottom);
        FormClosed += (_, _) => cts.Cancel();
        Shown += (_, _) => { TopMost = false; Activate(); Load2(); };
        Resize += (_, _) => FitRows();
    }

    void FitRows() { foreach (Control c in rows.Controls) c.Width = rows.ClientSize.Width - 36; }

    string Target(out string tRef, out string tCookies)
    {
        tRef = referer ?? pageUrl; tCookies = cookies;
        if (!string.IsNullOrEmpty(pageUrl) && Ytdlp.IsVideoSite(pageUrl)) return pageUrl;
        var stream = media.FirstOrDefault(m => m.Kind is "hls" or "dash");
        if (stream != null) { tRef = stream.Referer ?? pageUrl; tCookies = stream.Cookies ?? cookies; return stream.Url; }
        if (media.Any(m => m.Kind == "file")) return null; // only direct files
        return pageUrl;
    }

    async void Load2()
    {
        rows.Controls.Clear();
        // direct files first (instant)
        var files = media.Where(m => m.Kind == "file").GroupBy(m => m.Url).Select(g => g.First()).ToList();
        if (files.Count > 0)
        {
            Section("VIDEO FILES ON THIS PAGE");
            foreach (var m in files)
            {
                var n = Http.FileNameFrom(null, m.Url, m.Type);
                if (!Path.HasExtension(n) || n.StartsWith("download")) n = Http.Sanitize((title ?? "video")) + ".mp4";
                AddRow(n, (m.Type ?? "file") + " • " + Host(m.Url), m.Size, false, false, () =>
                {
                    var f = new AddUrlForm(m.Url, m.Referer ?? pageUrl, m.Cookies ?? cookies, ua, n, m.Size); f.Show(); Close();
                });
            }
        }
        var target = Target(out var tRef, out var tCookies);
        if (target == null) { bar.Visible = false; status.Text = $"{files.Count} video file(s) found"; return; }
        status.Text = "Finding all qualities…";
        try
        {
            if (!Tools.Ready)
                await Task.Run(() => Tools.EnsureReady((t, p) => BeginInvoke(() =>
                {
                    status.Text = "First-time setup of the video engine — " + t;
                    if (p >= 0) { bar.Style = ProgressBarStyle.Continuous; bar.Value = Math.Clamp(p, 0, 100); } else bar.Style = ProgressBarStyle.Marquee;
                }), cts.Token));
            bar.Style = ProgressBarStyle.Marquee; status.Text = "Finding all qualities…";
            var info = await Task.Run(() => Ytdlp.Fetch(target, tRef, tCookies, ua, cts.Token));
            if (IsDisposed) return;
            bar.Visible = false;
            ShowInfo(info, target, tRef, tCookies);
        }
        catch (Exception e)
        {
            if (IsDisposed || cts.IsCancellationRequested) return;
            bar.Visible = false;
            if (files.Count > 0) { status.Text = $"{files.Count} video file(s) found"; return; }
            status.Text = "Could not read this page: " + e.Message;
            status.ForeColor = Theme.Red; status.Height = 60;
            if (fallbackLinks || !Ytdlp.IsVideoSite(pageUrl ?? ""))
            {
                var b = Theme.Btn("Find downloadable files on this page", true, 300);
                b.Click += (_, _) => { new GrabberForm(pageUrl).Show(); Close(); };
                rows.Controls.Add(b);
            }
            var u = Theme.Btn("Update video engine & retry", false, 260);
            u.Click += async (_, _) => { status.Text = "Updating yt-dlp…"; bar.Visible = true; await Task.Run(() => { try { Tools.Update(); } catch { } }); status.ForeColor = Theme.Gray; Load2(); };
            rows.Controls.Add(u);
        }
    }

    static string Host(string u) { try { return new Uri(u).Host; } catch { return ""; } }

    void Section(string t) => rows.Controls.Add(new Label { Text = t, AutoSize = false, Width = rows.ClientSize.Width - 36, Height = 28, Font = new Font("Segoe UI Semibold", 9f), ForeColor = Theme.Orange, TextAlign = ContentAlignment.BottomLeft });

    void ShowInfo(VideoInfo info, string target, string tRef, string tCookies)
    {
        status.Text = $"{info.Qualities.Count} options found — click one to download";
        if (!string.IsNullOrEmpty(info.Thumbnail))
        {
            var pic = new PictureBox { Width = rows.ClientSize.Width - 36, Height = 250, SizeMode = PictureBoxSizeMode.Zoom, BackColor = Color.Black };
            rows.Controls.Add(pic);
            Task.Run(async () =>
            {
                try { var bytes = await Http.Client.GetByteArrayAsync(info.Thumbnail); var img = Image.FromStream(new MemoryStream(bytes)); BeginInvoke(() => pic.Image = img); } catch { }
            });
        }
        rows.Controls.Add(new Label { Text = info.Title, AutoSize = false, Width = rows.ClientSize.Width - 36, Height = 48, Font = new Font("Segoe UI Semibold", 11.5f) });
        var meta = string.Join("  •  ", new[] { info.Uploader, info.Duration > 0 ? Fmt.Time(info.Duration) : null }.Where(x => !string.IsNullOrEmpty(x)));
        if (meta.Length > 0) rows.Controls.Add(new Label { Text = meta, AutoSize = true, ForeColor = Theme.Gray });
        bool vh = false, ah = false;
        foreach (var q in info.Qualities)
        {
            if (!q.AudioOnly && !vh) { vh = true; Section("VIDEO"); }
            if (q.AudioOnly && !ah) { ah = true; Section("AUDIO ONLY"); }
            AddRow(q.Label, q.Detail, q.Size, q.Approx, q.AudioOnly, () => Start(info, q, target, tRef, tCookies));
        }
        FitRows();
    }

    void AddRow(string label, string detail, long size, bool approx, bool audio, Action click)
    {
        var p = new Panel { Width = rows.ClientSize.Width - 36, Height = 58, Margin = new Padding(0, 4, 0, 4), Cursor = Cursors.Hand, BackColor = Color.White };
        p.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var path = Theme.Rounded(new Rectangle(0, 0, p.Width - 1, p.Height - 1), 8);
            using var pen = new Pen(Color.FromArgb(0xD6, 0xDD, 0xE7));
            e.Graphics.DrawPath(pen, path);
        };
        var (g, col) = Theme.Cat(audio ? "Music" : "Video");
        var icon = new PictureBox { Image = Theme.Badge(g, col, 34), Size = new Size(34, 34), Location = new Point(12, 12) };
        var l1 = new Label { Text = label, Font = new Font("Segoe UI Semibold", 11f), AutoSize = true, Location = new Point(56, 8) };
        var l2 = new Label { Text = detail, ForeColor = Theme.Gray, AutoSize = true, Location = new Point(57, 32) };
        var sz = new Label { Text = size > 0 ? (approx ? "~" : "") + Fmt.Size(size) : "", Font = new Font("Segoe UI Semibold", 10f), AutoSize = true, Anchor = AnchorStyles.Right | AnchorStyles.Top };
        var dl = Theme.Btn("Download", true, 100); dl.Height = 32; dl.Anchor = AnchorStyles.Right | AnchorStyles.Top;
        p.Controls.AddRange(new Control[] { icon, l1, l2, sz, dl });
        void Lay() { dl.Location = new Point(p.Width - 112, 13); sz.Location = new Point(dl.Left - sz.Width - 12, 19); }
        p.Resize += (_, _) => Lay(); sz.SizeChanged += (_, _) => Lay(); Lay();
        foreach (Control c in new Control[] { p, icon, l1, l2, sz }) c.Click += (_, _) => click();
        dl.Click += (_, _) => click();
        rows.Controls.Add(p);
    }

    void Start(VideoInfo info, Quality q, string target, string tRef, string tCookies)
    {
        var suffix = q.AudioOnly ? "" : $" [{q.Label}]";
        var cat = q.AudioOnly ? "Music" : "Video";
        var d = new DownloadItem
        {
            Url = target, FileName = Http.Sanitize((info.Title.Length > 150 ? info.Title[..150] : info.Title) + suffix + "." + q.Ext), Category = cat,
            SaveDir = App.Settings.FolderFor(cat), TotalSize = q.Size, Resumable = true, Connections = Math.Min(16, App.Settings.MaxConnections),
            Kind = "ytdlp", FormatSpec = q.FormatSpec, Extra = q.Extra, InQueue = queue.Checked, Description = info.Title,
            Referer = target == pageUrl && Ytdlp.IsVideoSite(target) ? null : tRef, Cookies = target == pageUrl && Ytdlp.IsVideoSite(target) ? null : tCookies,
            UserAgent = target == pageUrl && Ytdlp.IsVideoSite(target) ? null : ua,
        };
        Manager.Add(d, !queue.Checked);
        if (!queue.Checked && App.Settings.ShowStartDialog) ProgressForm.ShowFor(d.Id);
        Close();
    }
}
