using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace MRMZoneIDM.UI;

public static class Theme
{
    public static readonly Color Bar = Color.FromArgb(0x2B, 0x2F, 0x36);   // same charcoal as the mobile app
    public static readonly Color Navy = Color.FromArgb(0x2B, 0x2F, 0x36);

    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    /// <summary>Navy window title bar with white text (Windows 11; silently ignored on Windows 10).</summary>
    public static void NavyCaption(Form f)
    {
        void Apply()
        {
            try
            {
                int cap = Navy.R | Navy.G << 8 | Navy.B << 16, txt = 0xFFFFFF;
                DwmSetWindowAttribute(f.Handle, 35, ref cap, 4);  // DWMWA_CAPTION_COLOR
                DwmSetWindowAttribute(f.Handle, 36, ref txt, 4);  // DWMWA_TEXT_COLOR
                DwmSetWindowAttribute(f.Handle, 34, ref cap, 4);  // DWMWA_BORDER_COLOR
            }
            catch { }
        }
        if (f.IsHandleCreated) Apply(); else f.HandleCreated += (_, _) => Apply();
    }
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
    public static void Fit(Form f) { NavyCaption(f); if (K > 1.01f) f.Scale(new SizeF(K, K)); }
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
        // cheap check (enumerating every installed font is slow at startup)
        try { using var f = new Font(name, 10f); return string.Equals(f.Name, name, StringComparison.OrdinalIgnoreCase); } catch { return false; }
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

    public static readonly Color Hover = Color.FromArgb(0xE6, 0xEF, 0xFB);
    public static readonly Color Pressed = Color.FromArgb(0xD2, 0xE2, 0xF8);
    public static readonly Color HoverBorder = Color.FromArgb(0xB9, 0xCF, 0xEE);
    public static readonly Color Panel = Color.FromArgb(0xF6, 0xF8, 0xFB);
    public static readonly Color Line = Color.FromArgb(0xDD, 0xE3, 0xEB);
    public static readonly Color Zebra = Color.FromArgb(0xF8, 0xFA, 0xFD);
    public static ToolStripRenderer Renderer => new ProRenderer();
}

/// <summary>Flat, modern look for toolbars, menus and the status bar (rounded hover, no gradients).</summary>
public class ProRenderer : ToolStripProfessionalRenderer
{
    public ProRenderer() : base(new ProColors()) { RoundedEdges = false; }

    protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
    {
        if (e.ToolStrip is ToolStripDropDown) { using var p = new Pen(Theme.Line); e.Graphics.DrawRectangle(p, 0, 0, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1); return; }
        if (e.ToolStrip is ToolStrip && e.ToolStrip is not StatusStrip && e.ToolStrip is not MenuStrip)
        { using var p = new Pen(Theme.Line); e.Graphics.DrawLine(p, 0, e.ToolStrip.Height - 1, e.ToolStrip.Width, e.ToolStrip.Height - 1); }
    }

    protected override void OnRenderButtonBackground(ToolStripItemRenderEventArgs e)
    {
        var it = e.Item;
        if (!it.Selected && !it.Pressed) return;
        e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        var r = new Rectangle(2, 2, it.Width - 5, it.Height - 5);
        using var path = Theme.Rounded(r, Theme.S(6));
        using var b = new SolidBrush(it.Pressed ? Theme.Pressed : Theme.Hover);
        using var p = new Pen(Theme.HoverBorder);
        e.Graphics.FillPath(b, path); e.Graphics.DrawPath(p, path);
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        using var p = new Pen(Theme.Line);
        if (e.Vertical) { int x = e.Item.Width / 2; e.Graphics.DrawLine(p, x, Theme.S(8), x, e.Item.Height - Theme.S(8)); }
        else { int y = e.Item.Height / 2; e.Graphics.DrawLine(p, Theme.S(28), y, e.Item.Width - 4, y); }
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        if (e.ToolStrip is StatusStrip) e.TextColor = e.Item is ToolStripStatusLabel { IsLink: true } ? Color.FromArgb(0xFF, 0xC8, 0x3C) : Color.FromArgb(0xD7, 0xDE, 0xE8);
        base.OnRenderItemText(e);
    }
}

public class ProColors : ProfessionalColorTable
{
    public override Color MenuItemSelected => Theme.Hover;
    public override Color MenuItemBorder => Theme.HoverBorder;
    public override Color MenuItemSelectedGradientBegin => Theme.Hover;
    public override Color MenuItemSelectedGradientEnd => Theme.Hover;
    public override Color MenuItemPressedGradientBegin => Color.White;
    public override Color MenuItemPressedGradientEnd => Color.White;
    public override Color MenuBorder => Theme.Line;
    public override Color ToolStripDropDownBackground => Color.White;
    public override Color ImageMarginGradientBegin => Color.White;
    public override Color ImageMarginGradientMiddle => Color.White;
    public override Color ImageMarginGradientEnd => Color.White;
    public override Color MenuStripGradientBegin => Color.White;
    public override Color MenuStripGradientEnd => Color.White;
    public override Color ToolStripGradientBegin => Theme.Panel;
    public override Color ToolStripGradientMiddle => Theme.Panel;
    public override Color ToolStripGradientEnd => Theme.Panel;
    public override Color ToolStripBorder => Theme.Line;
    public override Color SeparatorDark => Theme.Line;
    public override Color SeparatorLight => Color.White;
    public override Color StatusStripGradientBegin => Theme.Bar;
    public override Color StatusStripGradientEnd => Theme.Bar;
    public override Color ButtonSelectedHighlight => Theme.Hover;
    public override Color ButtonSelectedBorder => Theme.HoverBorder;
    public override Color ButtonPressedHighlight => Theme.Pressed;
    public override Color CheckBackground => Theme.Hover;
    public override Color CheckSelectedBackground => Theme.Pressed;
}
