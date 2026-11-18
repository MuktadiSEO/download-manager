using MRMZoneIDM.Engine;

namespace MRMZoneIDM.UI;

/// <summary>IDM-like download progress window with the per-connection segment bar.</summary>
public class ProgressForm : Form
{
    static readonly Dictionary<long, ProgressForm> Open = new();
    readonly long id;
    readonly Dictionary<string, Label> vals = new();
    readonly ProgressBar bar = new() { Width = 560, Height = 18, Maximum = 1000 };
    readonly SegmentBar seg = new() { Width = 560, Height = 22 };
    readonly ListView conns = new() { View = View.Details, Width = 560, Height = 130, FullRowSelect = true, HeaderStyle = ColumnHeaderStyle.Nonclickable };
    readonly Button pause = Theme.Btn("Pause", true, 110);
    readonly CheckBox limit = new() { Text = "Use speed limiter", AutoSize = true };
    readonly NumericUpDown limitVal = new() { Minimum = 10, Maximum = 1000000, Value = 500, Width = 90 };
    readonly System.Windows.Forms.Timer t = new() { Interval = 500 };

    public static void ShowFor(long id)
    {
        if (Open.TryGetValue(id, out var f) && !f.IsDisposed) { f.Show(); f.Activate(); return; }
        f = new ProgressForm(id); Open[id] = f; f.Show();
    }

    ProgressForm(long id)
    {
        this.id = id;
        var d = Manager.Get(id);
        Text = (d?.Progress ?? 0) + "% " + d?.FileName; Icon = Theme.AppIcon; Font = Theme.UiFont; BackColor = Color.White;
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false; StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(600, 560);
        var tabsTitle = new Label { Text = "Download status", Font = Theme.Bold, AutoSize = true, Location = new Point(20, 12) };
        Controls.Add(tabsTitle);
        int y = 40;
        foreach (var k in new[] { "File", "URL", "Status", "File size", "Downloaded", "Transfer rate", "Time left", "Resume capability", "Save to" })
        {
            Controls.Add(new Label { Text = k, AutoSize = true, ForeColor = Theme.Gray, Location = new Point(20, y) });
            var v = new Label { AutoSize = false, Width = 420, Height = 20, Location = new Point(160, y), Font = new Font("Segoe UI Semibold", 9.5f), AutoEllipsis = true };
            vals[k] = v; Controls.Add(v); y += 24;
        }
        bar.Location = new Point(20, y + 6); Controls.Add(bar); y += 34;
        Controls.Add(new Label { Text = "Start positions and download progress by connections", AutoSize = true, ForeColor = Theme.Gray, Location = new Point(20, y) }); y += 20;
        seg.Location = new Point(20, y); Controls.Add(seg); y += 30;
        conns.Columns.Add("N.", 40); conns.Columns.Add("Downloaded", 120); conns.Columns.Add("Info", 380);
        conns.Location = new Point(20, y); Controls.Add(conns); y += 140;
        limit.Location = new Point(20, y + 6); limitVal.Location = new Point(160, y + 3);
        Controls.Add(limit); Controls.Add(limitVal); Controls.Add(new Label { Text = "KB/sec", AutoSize = true, Location = new Point(256, y + 7) });
        var cbDone = new CheckBox { Text = "Show download complete dialog", AutoSize = true, Location = new Point(330, y + 6), Checked = App.Settings.ShowCompleteDialog };
        cbDone.CheckedChanged += (_, _) => { App.Settings.ShowCompleteDialog = cbDone.Checked; Store.Save(App.Settings); };
        Controls.Add(cbDone);
        y += 40;
        var hide = Theme.Btn("Hide", false, 100); hide.Location = new Point(250, y); hide.Click += (_, _) => Close();
        pause.Location = new Point(360, y);
        var cancel = Theme.Btn("Cancel", false, 100); cancel.Location = new Point(480, y);
        Controls.AddRange(new Control[] { hide, pause, cancel });
        pause.Click += (_, _) => { var x = Manager.Get(id); if (x == null) return; if (x.Status.IsActive()) Manager.Pause(id); else Manager.Start(id); };
        cancel.Click += (_, _) =>
        {
            var r = MessageBox.Show(this, "Stop this download?\n\nYes = stop (keep in list, can resume later)\nNo = stop and remove it", "Cancel download", MessageBoxButtons.YesNoCancel, MessageBoxIcon.Question);
            if (r == DialogResult.Yes) { Manager.Pause(id); Close(); }
            else if (r == DialogResult.No) { Manager.Delete(id, true); Close(); }
        };
        if (d != null) { limit.Checked = d.SpeedLimitKb > 0; if (d.SpeedLimitKb > 0) limitVal.Value = Math.Clamp(d.SpeedLimitKb, 10, 1000000); }
        void ApplyLimit() => Manager.SetItemLimit(id, limit.Checked ? (int)limitVal.Value : 0);
        limit.CheckedChanged += (_, _) => ApplyLimit(); limitVal.ValueChanged += (_, _) => { if (limit.Checked) ApplyLimit(); };
        t.Tick += (_, _) => Render();
        t.Start(); Render();
        FormClosed += (_, _) => { t.Stop(); Open.Remove(id); };
    }

    void Render()
    {
        var d = Manager.Get(id);
        if (d == null) { Close(); return; }
        if (d.Status == DStatus.Completed) { Close(); return; }
        Text = $"{d.Progress}% {d.FileName}";
        vals["File"].Text = d.FileName;
        vals["URL"].Text = d.Url;
        vals["Status"].Text = d.Status == DStatus.Error ? "Error: " + d.Error : d.Status.Text();
        vals["Status"].ForeColor = d.Status == DStatus.Error ? Theme.Red : Color.Black;
        vals["File size"].Text = Fmt.Size(d.TotalSize);
        vals["Downloaded"].Text = Fmt.Size(d.Downloaded) + (d.TotalSize > 0 ? $"  ( {d.Downloaded * 100.0 / d.TotalSize:0.00} % )" : "");
        vals["Transfer rate"].Text = d.Status == DStatus.Downloading ? Fmt.Speed(d.Speed) : "";
        vals["Time left"].Text = d.Status == DStatus.Downloading ? Fmt.Time(d.TimeLeftSec) : "";
        vals["Resume capability"].Text = d.IsYtdlp || d.Resumable ? "Yes" : d.TotalSize < 0 && d.Downloaded == 0 ? "Unknown" : "No";
        vals["Save to"].Text = d.SaveDir;
        bar.Style = d.Status == DStatus.Connecting || d.Status == DStatus.Merging || (d.Status == DStatus.Downloading && d.TotalSize <= 0) ? ProgressBarStyle.Marquee : ProgressBarStyle.Continuous;
        if (d.TotalSize > 0) bar.Value = (int)Math.Clamp(d.Downloaded * 1000 / d.TotalSize, 0, 1000);
        pause.Text = d.Status.IsActive() ? "Pause" : "Resume";
        var segs = d.LiveSegments.Count > 0 ? d.LiveSegments : d.Segments ?? new();
        seg.Set(segs, d.TotalSize, d.IsYtdlp ? d.Progress : -1);
        conns.BeginUpdate();
        while (conns.Items.Count < segs.Count) conns.Items.Add(new ListViewItem(new[] { "", "", "" }));
        while (conns.Items.Count > segs.Count) conns.Items.RemoveAt(conns.Items.Count - 1);
        for (int i = 0; i < segs.Count; i++)
        {
            var s = segs[i]; var it = conns.Items[i];
            it.SubItems[0].Text = (i + 1).ToString();
            it.SubItems[1].Text = Fmt.Size(s.Done);
            it.SubItems[2].Text = s.Finished ? "Done" : d.Status == DStatus.Downloading ? "Receiving data…" : "Idle";
        }
        conns.EndUpdate();
    }
}

public class SegmentBar : Control
{
    List<Segment> segs = new(); long total = -1; int pct = -1;
    public SegmentBar() { DoubleBuffered = true; }
    public void Set(List<Segment> s, long t, int percent) { segs = s; total = t; pct = percent; Invalidate(); }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics; float w = Width, h = Height;
        using var bg = new SolidBrush(Theme.SegBg); using var fill = new SolidBrush(Theme.Blue); using var head = new SolidBrush(Theme.Orange); using var sep = new Pen(Color.White);
        g.FillRectangle(bg, 0, 0, w, h);
        if (pct >= 0) { g.FillRectangle(fill, 0, 0, w * pct / 100f, h); return; }
        if (total <= 0) return;
        foreach (var s in segs)
        {
            float x0 = s.Start * w / total, x1 = Math.Min(s.Start + s.Done, total) * w / total;
            if (x1 > x0) g.FillRectangle(fill, x0, 0, x1 - x0, h);
            if (!s.Finished && s.Done > 0) g.FillRectangle(head, Math.Max(x0, x1 - 3), 0, 3, h);
            if (s.Start > 0) g.DrawLine(sep, x0, 0, x0, h);
        }
        g.DrawRectangle(Pens.Silver, 0, 0, Width - 1, Height - 1);
    }
}

/// <summary>"Download complete" dialog: Open / Open with / Open folder / Close.</summary>
public class CompleteForm : Form
{
    public CompleteForm(DownloadItem d)
    {
        Text = "Download complete"; Icon = Theme.AppIcon; Font = Theme.UiFont; BackColor = Color.White; TopMost = true;
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; MinimizeBox = false; StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(560, 290);
        Controls.Add(Theme.Header("Download complete", d.FileName));
        int y = 82;
        void L(string k, string v) { Controls.Add(new Label { Text = k, ForeColor = Theme.Gray, AutoSize = true, Location = new Point(20, y) }); Controls.Add(new Label { Text = v, AutoSize = false, Width = 420, Height = 20, AutoEllipsis = true, Location = new Point(120, y), Font = new Font("Segoe UI Semibold", 9.5f) }); y += 24; }
        L("Address", d.Url); L("File size", Fmt.Size(d.TotalSize)); L("Saved to", d.FullPath);
        var cb = new CheckBox { Text = "Don't show this dialog again", AutoSize = true, Location = new Point(20, y + 10) };
        Controls.Add(cb);
        y += 50;
        var open = Theme.Btn("Open", true, 100); open.Location = new Point(20, y);
        var with = Theme.Btn("Open with…", false, 110); with.Location = new Point(130, y);
        var folder = Theme.Btn("Open folder", false, 110); folder.Location = new Point(250, y);
        var close = Theme.Btn("Close", false, 100); close.Location = new Point(440, y);
        Controls.AddRange(new Control[] { open, with, folder, close });
        void Done() { if (cb.Checked) { App.Settings.ShowCompleteDialog = false; Store.Save(App.Settings); } Close(); }
        open.Click += (_, _) => { Theme.Open(d.FullPath); Done(); };
        with.Click += (_, _) => { try { System.Diagnostics.Process.Start("rundll32.exe", $"shell32.dll,OpenAs_RunDLL {d.FullPath}"); } catch { } Done(); };
        folder.Click += (_, _) => { Theme.ShowInFolder(d.FullPath); Done(); };
        close.Click += (_, _) => Done();
        AcceptButton = open; CancelButton = close;
        Shown += (_, _) => { System.Media.SystemSounds.Asterisk.Play(); };
    }
}
