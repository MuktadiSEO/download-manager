using MRMZoneIDM.Engine;

namespace MRMZoneIDM.UI;

/// <summary>IDM "Download File Info" dialog.</summary>
public class AddUrlForm : Form
{
    readonly TextBox url = new() { Width = 560 }, name = new() { Width = 560 }, folder = new() { Width = 470 }, desc = new() { Width = 560 };
    readonly ComboBox cat = new() { Width = 200, DropDownStyle = ComboBoxStyle.DropDownList };
    readonly NumericUpDown conns = new() { Minimum = 1, Maximum = 32, Width = 70 };
    readonly Label info = new() { AutoSize = true, ForeColor = Theme.Gray, MaximumSize = new Size(560, 0) };
    readonly CheckBox queue = new() { Text = "Add to main queue", AutoSize = true };
    readonly ProgressBar busy = new() { Style = ProgressBarStyle.Marquee, Width = 120, Height = 8, Visible = false };
    string referer, cookies, ua;
    long size = -1; bool resumable; bool nameEdited, folderEdited;
    readonly CheckBox rememberFolder = new() { Text = "Remember this folder for this category", AutoSize = true, Checked = true, ForeColor = Theme.Gray };
    CancellationTokenSource probeCts;
    readonly System.Windows.Forms.Timer debounce = new() { Interval = 700 };

    public AddUrlForm(string u, string referer, string cookies, string ua, string fileName, long knownSize)
    {
        this.referer = referer; this.cookies = cookies; this.ua = ua; size = knownSize;
        Text = "Download File Info — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont;
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(640, 470); BackColor = Color.White; TopMost = true;
        Controls.Add(Theme.Header("Download File Info", "MRM Zone Internet Download Manager"));
        int y = 80;
        Control Row(string label, Control c, Control extra = null)
        {
            Controls.Add(new Label { Text = label, AutoSize = true, Location = new Point(20, y + 4), ForeColor = Theme.Gray });
            c.Location = new Point(20, y + 24); Controls.Add(c);
            if (extra != null) { extra.Location = new Point(c.Right + 8, y + 22); Controls.Add(extra); }
            y += 56; return c;
        }
        Row("Address (URL)", url);
        info.Location = new Point(20, y - 4); Controls.Add(info); busy.Location = new Point(500, y - 2); Controls.Add(busy); y += 20;
        Row("Save as", name);
        var browse = Theme.Btn("Browse…", false, 82); browse.Height = 27;
        browse.Click += (_, _) => { using var d = new FolderBrowserDialog { SelectedPath = folder.Text }; if (d.ShowDialog(this) == DialogResult.OK) { folder.Text = d.SelectedPath; folderEdited = true; } };
        Row("Save to folder", folder, browse);
        rememberFolder.Location = new Point(20, y - 8); Controls.Add(rememberFolder); y += 20;
        Controls.Add(new Label { Text = "Category", AutoSize = true, Location = new Point(20, y + 4), ForeColor = Theme.Gray });
        cat.Location = new Point(20, y + 24); cat.Items.AddRange(Categories.Names); Controls.Add(cat);
        Controls.Add(new Label { Text = "Connections", AutoSize = true, Location = new Point(240, y + 4), ForeColor = Theme.Gray });
        conns.Location = new Point(240, y + 24); conns.Value = Math.Clamp(App.Settings.MaxConnections, 1, 32); Controls.Add(conns);
        queue.Location = new Point(340, y + 26); Controls.Add(queue);
        y += 56;
        Row("Description (optional)", desc);
        var start = Theme.Btn("Start Download", true, 150); start.Location = new Point(470, y + 6);
        var later = Theme.Btn("Download Later", false, 130); later.Location = new Point(330, y + 6);
        var cancel = Theme.Btn("Cancel", false, 90); cancel.Location = new Point(230, y + 6);
        Controls.AddRange(new Control[] { start, later, cancel });
        AcceptButton = start; CancelButton = cancel;
        cancel.Click += (_, _) => Close();
        start.Click += (_, _) => Add(true);
        later.Click += (_, _) => Add(false);
        cat.SelectedIndexChanged += (_, _) => { if (!folderEdited) folder.Text = App.Settings.FolderFor(cat.SelectedItem as string); };
        name.TextChanged += (_, _) => { if (name.Focused) { nameEdited = true; cat.SelectedItem = Categories.FromFileName(name.Text); } };
        url.TextChanged += (_, _) => { debounce.Stop(); debounce.Start(); };
        debounce.Tick += (_, _) => { debounce.Stop(); Probe(); };
        cat.SelectedItem = "General";
        if (!string.IsNullOrEmpty(fileName)) { name.Text = Http.Sanitize(fileName); nameEdited = true; cat.SelectedItem = Categories.FromFileName(fileName); }
        if (string.IsNullOrEmpty(u)) try { if (Clipboard.ContainsText()) u = Http.ExtractUrl(Clipboard.GetText()); } catch { } // clipboard may be locked
        url.Text = u ?? "";
        Shown += (_, _) => { TopMost = false; Activate(); if (url.Text.Length == 0) url.Focus(); };
        FormClosed += (_, _) => { debounce.Stop(); debounce.Dispose(); probeCts?.Cancel(); };
        Theme.Fit(this);
    }

    async void Probe()
    {
        if (IsDisposed) return;
        var u = url.Text.Trim();
        if (!u.StartsWith("http", StringComparison.OrdinalIgnoreCase)) return;
        if (Ytdlp.IsVideoSite(u)) { VideoForm.Open(u, null, referer, cookies, ua, null); Close(); return; }
        probeCts?.Cancel(); probeCts = new CancellationTokenSource();
        var ct = probeCts.Token;
        busy.Visible = true; info.Text = "Getting file information…";
        try
        {
            var p = await Http.Probe(u, ua, referer, cookies, ct);
            if (ct.IsCancellationRequested || IsDisposed) return;
            if (p.ContentType != null && p.ContentType.Contains("text/html"))
            {
                info.Text = "This is a web page — looking for videos on it…";
                VideoForm.Open(p.FinalUrl, null, referer, cookies, ua, null, fallbackLinks: true);
                Close(); return;
            }
            size = p.Size; resumable = p.Resumable;
            if (!nameEdited) { name.Text = p.FileName; cat.SelectedItem = Categories.FromFileName(p.FileName); }
            info.Text = $"Size: {Fmt.Size(p.Size)}     Resume capability: {(p.Resumable ? "Yes ✔" : "No")}     {p.ContentType}";
            info.ForeColor = Theme.Gray;
        }
        catch (Exception e) when (!ct.IsCancellationRequested && !IsDisposed)
        {
            info.Text = "Could not get file info: " + e.Message + "  (you can still try to download)";
            info.ForeColor = Theme.Red;
            if (!nameEdited && name.Text.Length == 0) name.Text = Http.FileNameFrom(null, u, null);
        }
        catch { }
        finally { if (!IsDisposed) busy.Visible = false; }
    }

    void Add(bool start)
    {
        var u = url.Text.Trim();
        if (!u.StartsWith("http", StringComparison.OrdinalIgnoreCase) && !u.StartsWith("ftp", StringComparison.OrdinalIgnoreCase)) { MessageBox.Show(this, "Please enter a valid http/https address.", Text); return; }
        var n = Http.Sanitize(string.IsNullOrWhiteSpace(name.Text) ? Http.FileNameFrom(null, u, null) : name.Text);
        var c = cat.SelectedItem as string ?? Categories.FromFileName(n);
        var d = new DownloadItem
        {
            Url = u, FileName = n, Category = c, SaveDir = string.IsNullOrWhiteSpace(folder.Text) ? App.Settings.FolderFor(c) : folder.Text,
            TotalSize = size, Resumable = resumable, Connections = (int)conns.Value, Referer = referer, Cookies = cookies, UserAgent = ua,
            InQueue = queue.Checked || !start, Description = desc.Text,
        };
        if (folderEdited && rememberFolder.Checked) App.Settings.RememberFolder(c, d.SaveDir);
        Manager.Add(d, start);
        if (start && App.Settings.ShowStartDialog) ProgressForm.ShowFor(d.Id);
        Close();
    }
}
