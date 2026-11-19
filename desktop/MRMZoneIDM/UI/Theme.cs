using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace MRMZoneIDM.UI;

public static class Theme
{
    public static readonly Color Bar = Color.FromArgb(0x2B, 0x2F, 0x36);
    public static readonly Color Blue = Color.FromArgb(0x15, 0x65, 0xC0);
    public static readonly Color Orange = Color.FromArgb(0xFF, 0x63, 0x47);
    public static readonly Color Green = Color.FromArgb(0x2E, 0x7D, 0x32);
    public static readonly Color Red = Color.FromArgb(0xD3, 0x2F, 0x2F);
    public static readonly Color Gray = Color.FromArgb(0x6B, 0x72, 0x80);
    public static readonly Color SegBg = Color.FromArgb(0xDC, 0xE3, 0xEC);
    public static readonly Font UiFont = new("Segoe UI", 9f);
    public static readonly Font Bold = new("Segoe UI Semibold", 9.5f);

    /// <summary>Screen scale (1.0 = 100%, 1.5 = 150%). Layouts are written for 100% and scaled with Fit / S.</summary>
    public static readonly float K = ComputeK();
    static float ComputeK()
    {
        try { using var g = Graphics.FromHwnd(IntPtr.Zero); return Math.Clamp(g.DpiX / 96f, 1f, 4f); } catch { return 1f; }
    }
    public static int S(int v) => (int)Math.Round(v * K);
    public static Size S(int w, int h) => new(S(w), S(h));
    /// <summary>Scale a form written in 100% pixels to the screen's DPI (fonts are already DPI-sized).</summary>
    public static void Fit(Form f) { if (K > 1.01f) f.Scale(new SizeF(K, K)); }
    public static readonly Font Title = new("Segoe UI", 14f, FontStyle.Bold);

    static Image logo;
    public static Image Logo => logo ??= LoadRes("logo.png");
    static Icon icon;
    public static Icon AppIcon => icon ??= LoadIcon();
    static Icon LoadIcon()
    {
        using var s = typeof(Theme).Assembly.GetManifestResourceStream("app.ico");
        return s != null ? new Icon(s) : (Icon)SystemIcons.Application.Clone();
    }

    static Image LoadRes(string name)
    {
        // GDI+ needs the source stream for the Image's whole lifetime; copy into a standalone bitmap
        // so disposing the resource stream cannot cause "A generic error occurred in GDI+" later.
        using var s = typeof(Theme).Assembly.GetManifestResourceStream(name);
        if (s == null) return new Bitmap(1, 1);
        using var img = Image.FromStream(s);
        return new Bitmap(img);
    }

    // Segoe MDL2 Assets / Segoe Fluent Icons glyphs (built into Windows 10/11)
    public const string GAdd = "", GPlay = "", GPause = "", GStop = "", GDelete = "", GBroom = "\uE894",
        GSettings = "", GClock = "", GQueuePlay = "", GQueueStop = "\uE71A", GGlobe = "", GShare = "",
        GFolder = "", GDownload = "", GPuzzle = "", GVideo = "", GMusic = "", GDoc = "", GZip = "",
        GApp = "", GImage = "", GFile = "", GList = "", GCheck = "", GError = "", GSearch = "",
        GBatch = "", GInfo = "", GLink = "";

    static readonly string IconFont = FontAvailable("Segoe Fluent Icons") ? "Segoe Fluent Icons" : "Segoe MDL2 Assets";

    static bool FontAvailable(string name)
    {
        using var f = new InstalledFontCollection();
        return f.Families.Any(x => x.Name == name);
    }

    public static Bitmap Glyph(string glyph, Color color, int size)
    {
        size = S(size);
        var bmp = new Bitmap(size, size);
        using var g = Graphics.FromImage(bmp);
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        using var f = new Font(IconFont, size * 0.62f, GraphicsUnit.Pixel);
        using var b = new SolidBrush(color);
        var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        g.DrawString(glyph, f, b, new RectangleF(0, 0, size, size), sf);
        return bmp;
    }

    /// <summary>Category badge: rounded colored square with a white glyph.</summary>
    public static Bitmap Badge(string glyph, Color bg, int size)
    {
        size = S(size);
        var bmp = new Bitmap(size, size);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var path = Rounded(new Rectangle(0, 0, size - 1, size - 1), size / 4))
        using (var b = new SolidBrush(bg)) g.FillPath(b, path);
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        using var f = new Font(IconFont, size * 0.5f, GraphicsUnit.Pixel);
        var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        g.DrawString(glyph, f, Brushes.White, new RectangleF(0, 0, size, size), sf);
        return bmp;
    }

    public static GraphicsPath Rounded(Rectangle r, int radius)
    {
        var p = new GraphicsPath(); int d = radius * 2;
        p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90); p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure(); return p;
    }

    public static (string glyph, Color color) Cat(string cat) => cat switch
    {
        "Video" => (GVideo, Color.FromArgb(0xE5, 0x39, 0x35)),
        "Music" => (GMusic, Color.FromArgb(0x8E, 0x24, 0xAA)),
        "Documents" => (GDoc, Color.FromArgb(0x1E, 0x88, 0xE5)),
        "Compressed" => (GZip, Color.FromArgb(0xF9, 0xA8, 0x25)),
        "Programs" => (GApp, Color.FromArgb(0x43, 0xA0, 0x47)),
        "Images" => (GImage, Color.FromArgb(0x00, 0xAC, 0xC1)),
        _ => (GFile, Color.FromArgb(0x75, 0x75, 0x75)),
    };

    public static Button Btn(string text, bool primary = false, int width = 110)
    {
        var b = new Button { Text = text, Width = width, Height = 34, FlatStyle = FlatStyle.Flat, Font = primary ? Bold : UiFont, Cursor = Cursors.Hand };
        if (primary) { b.BackColor = Orange; b.ForeColor = Color.White; b.FlatAppearance.BorderSize = 0; }
        else { b.BackColor = Color.White; b.FlatAppearance.BorderColor = Color.FromArgb(0xC8, 0xCE, 0xD8); }
        return b;
    }

    /// <summary>Dark branded header strip used on dialogs.</summary>
    public static Panel Header(string title, string subtitle)
    {
        var p = new Panel { Dock = DockStyle.Top, Height = 64, BackColor = Bar, Padding = new Padding(12, 8, 12, 8) };
        var pic = new PictureBox { Image = Logo, SizeMode = PictureBoxSizeMode.Zoom, Size = new Size(46, 46), Location = new Point(12, 9) };
        var t = new Label { Text = title, ForeColor = Color.White, Font = new Font("Segoe UI", 13f, FontStyle.Bold), AutoSize = true, Location = new Point(66, 9) };
        var s = new Label { Text = subtitle, ForeColor = Color.FromArgb(0xB8, 0xC4, 0xD6), Font = new Font("Segoe UI", 9f), AutoSize = true, Location = new Point(68, 36) };
        p.Controls.AddRange(new Control[] { pic, t, s });
        return p;
    }

    public static void Open(string pathOrUrl)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(pathOrUrl) { UseShellExecute = true }); }
        catch (Exception e) { MessageBox.Show("Could not open: " + e.Message, "MRM Zone IDM"); }
    }

    public static void ShowInFolder(string file)
    {
        try
        {
            if (File.Exists(file)) System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{file}\"");
            else System.Diagnostics.Process.Start("explorer.exe", $"\"{Path.GetDirectoryName(file)}\"");
        }
        catch { }
    }
}
