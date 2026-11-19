using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;
using MRMZoneIDM.Engine;

namespace MRMZoneIDM.UI;

public class MainForm : Form
{
    readonly ListView list = new() { View = View.Details, FullRowSelect = true, Dock = DockStyle.Fill, HideSelection = false, BorderStyle = BorderStyle.None, Font = Theme.UiFont };
    readonly TreeView tree = new() { Dock = DockStyle.Fill, BorderStyle = BorderStyle.None, Font = Theme.UiFont, HideSelection = false, ShowLines = false, ItemHeight = Theme.S(26) };
    readonly ToolStripStatusLabel stSpeed = new(), stActive = new(), stExt = new() { Spring = true, TextAlign = ContentAlignment.MiddleRight, IsLink = true };
    readonly NotifyIcon tray = new();
    readonly System.Windows.Forms.Timer ui = new() { Interval = 500 };
    string filter = "all";
    string search = "";
    readonly bool startHidden;
    bool reallyExit;
    string lastClip;
    volatile bool changed = true;
    readonly Dictionary<long, ListViewItem> rows = new();

    public MainForm(bool startHidden)
    {
        this.startHidden = startHidden;
        Text = "MRM Zone Internet Download Manager";
        Icon = Theme.AppIcon;
        Size = new Size(1100, 640); MinimumSize = new Size(760, 420);
        StartPosition = FormStartPosition.CenterScreen;
        Font = Theme.UiFont;
        BackColor = Color.White;
        BuildUi();
        Theme.Fit(this);
        Manager.Changed += () => changed = true;
        Manager.Completed += d => BeginInvokeSafe(() => OnCompleted(d));
        Manager.Failed += d => BeginInvokeSafe(() => tray.ShowBalloonTip(4000, "Download failed", d.FileName + "\n" + d.Error, ToolTipIcon.Error));
        LocalServer.OnAdd += j => BeginInvokeSafe(() => FromExtensionAdd(j));
        LocalServer.OnVideo += j => BeginInvokeSafe(() => FromExtensionVideo(j));
        LocalServer.OnLinks += j => BeginInvokeSafe(() => FromExtensionLinks(j));
        LocalServer.OnShow += () => BeginInvokeSafe(ShowMain);
        ui.Tick += (_, _) => { if (changed) { changed = false; Refresh2(); } UpdateStatus(); };
        ui.Start();
        AllowDrop = true;
        DragEventHandler enter = (_, e) => e.Effect = e.Data.GetDataPresent(DataFormats.Text) ? DragDropEffects.Copy : DragDropEffects.None;
        DragEventHandler drop = (_, e) => { var u = Http.ExtractUrl(e.Data.GetData(DataFormats.Text) as string); if (u != null) OpenAdd(u); };
        DragEnter += enter; DragDrop += drop;
        list.AllowDrop = true; list.DragEnter += enter; list.DragDrop += drop; // the list covers the form's client area
        ApplyStartup();
    }

    // Events from the engine / local server arrive on background threads. Never run UI code on them:
    // marshal to the UI thread, or queue until the window handle exists (startup, before Application.Run).
    readonly List<Action> pending = new();
    void BeginInvokeSafe(Action a)
    {
        lock (pending)
        {
            if (IsDisposed || Disposing) return;
            if (!IsHandleCreated) { pending.Add(a); return; }
        }
        try { BeginInvoke(a); } catch { }
    }

    protected override void SetVisibleCore(bool value)
    {
        if (startHidden && !IsHandleCreated) { CreateHandle(); value = false; }
        base.SetVisibleCore(value);
    }

    // ---------------- UI ----------------
    static void DoubleBuffer(Control c)
    {
        try { typeof(Control).GetProperty("DoubleBuffered", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)?.SetValue(c, true); } catch { }
    }

    void BuildUi()
    {
        DoubleBuffer(list); DoubleBuffer(tree);
        // header
        var header = Theme.Header("MRM Zone IDM", "Internet Download Manager  •  v" + App.Version);

        // menu
        var menu = new MenuStrip { Font = Theme.UiFont, BackColor = Color.White };
        var mTasks = new ToolStripMenuItem("&Tasks");
        mTasks.DropDownItems.Add(new ToolStripMenuItem("Add new download…", null, (_, _) => OpenAdd(null)) { ShortcutKeys = Keys.Control | Keys.N });
        mTasks.DropDownItems.Add("Download video (YouTube & more)…", null, (_, _) => OpenVideoPrompt());
        mTasks.DropDownItems.Add("Add batch download…", null, (_, _) => new BatchForm().ShowDialog(this));
        mTasks.DropDownItems.Add("Site grabber…", null, (_, _) => new GrabberForm().Show(this));
        mTasks.DropDownItems.Add(new ToolStripSeparator());
        mTasks.DropDownItems.Add("Exit", null, (_, _) => ExitApp());
        var mFile = new ToolStripMenuItem("&Downloads");
        mFile.DropDownItems.Add("Resume all", null, (_, _) => Manager.ResumeAll());
        mFile.DropDownItems.Add("Stop all", null, (_, _) => Manager.PauseAll());
        mFile.DropDownItems.Add("Delete completed", null, (_, _) => ConfirmDeleteCompleted());
        mFile.DropDownItems.Add(new ToolStripSeparator());
        mFile.DropDownItems.Add("Scheduler…", null, (_, _) => new SchedulerForm().ShowDialog(this));
        mFile.DropDownItems.Add("Start queue", null, (_, _) => Manager.StartQueue());
        mFile.DropDownItems.Add("Stop queue", null, (_, _) => Manager.StopQueue());
        mFile.DropDownItems.Add(new ToolStripSeparator());
        mFile.DropDownItems.Add("Options…", null, (_, _) => new OptionsForm().ShowDialog(this));
        mFile.DropDownItems.Add("Open download folder", null, (_, _) => { Directory.CreateDirectory(App.Settings.BaseFolder); Theme.Open(App.Settings.BaseFolder); });
        var mHelp = new ToolStripMenuItem("&Help");
        mHelp.DropDownItems.Add("Install browser extension (Chrome / Edge)…", null, (_, _) => new ExtensionForm().ShowDialog(this));
        mHelp.DropDownItems.Add("Update video engine (yt-dlp)…", null, (_, _) => UpdateEngine());
        mHelp.DropDownItems.Add("About MRM Zone IDM", null, (_, _) => About());
        menu.Items.AddRange(new ToolStripItem[] { mTasks, mFile, mHelp });

        // toolbar (IDM style)
        var tb = new ToolStrip { ImageScalingSize = Theme.S(26, 26), GripStyle = ToolStripGripStyle.Hidden, BackColor = Color.FromArgb(0xF6, 0xF8, 0xFB), Padding = new Padding(6, 4, 6, 4), Font = new Font("Segoe UI", 9f), RenderMode = ToolStripRenderMode.System };
        void T(string text, string glyph, Color c, Action a)
        {
            var b = new ToolStripButton(text, Theme.Glyph(glyph, c, 26), (_, _) => a()) { TextImageRelation = TextImageRelation.ImageAboveText, ImageScaling = ToolStripItemImageScaling.None, Padding = new Padding(Theme.S(7), Theme.S(2), Theme.S(7), Theme.S(2)) };
            tb.Items.Add(b);
        }
        T("Add URL", Theme.GAdd, Theme.Orange, () => OpenAdd(null));
        T("Video", Theme.GVideo, Color.FromArgb(0xE5, 0x39, 0x35), OpenVideoPrompt);
        T("Resume", Theme.GPlay, Theme.Green, () => ForSelected(d => Manager.Start(d.Id)));
        T("Stop", Theme.GPause, Theme.Blue, () => ForSelected(d => Manager.Pause(d.Id)));
        T("Stop All", Theme.GStop, Theme.Red, Manager.PauseAll);
        T("Delete", Theme.GDelete, Theme.Red, DeleteSelected);
        T("Delete Completed", Theme.GBroom, Theme.Blue, ConfirmDeleteCompleted);
        tb.Items.Add(new ToolStripSeparator());
        T("Options", Theme.GSettings, Theme.Gray, () => new OptionsForm().ShowDialog(this));
        T("Scheduler", Theme.GClock, Theme.Blue, () => new SchedulerForm().ShowDialog(this));
        T("Start Queue", Theme.GQueuePlay, Theme.Green, Manager.StartQueue);
        T("Stop Queue", Theme.GQueueStop, Theme.Red, Manager.StopQueue);
        tb.Items.Add(new ToolStripSeparator());
        T("Grabber", Theme.GGlobe, Theme.Blue, () => new GrabberForm().Show(this));
        T("Batch", Theme.GBatch, Theme.Blue, () => new BatchForm().ShowDialog(this));
        T("Extension", Theme.GPuzzle, Theme.Orange, () => new ExtensionForm().ShowDialog(this));
        T("Tell a Friend", Theme.GShare, Theme.Gray, () => { if (TrySetClipboard("MRM Zone Internet Download Manager — fast downloads, YouTube in all qualities!")) MessageBox.Show(this, "Message copied — paste it to your friends!", "Tell a friend"); });
        var searchBox = new ToolStripTextBox { Alignment = ToolStripItemAlignment.Right, AutoSize = false, Width = Theme.S(190), ToolTipText = "Search downloads" };
        searchBox.TextBox.PlaceholderText = "Search downloads";
        searchBox.TextChanged += (_, _) => { search = searchBox.Text; changed = true; };
        tb.Items.Add(searchBox);

        // tree
        var imgs = new ImageList { ImageSize = Theme.S(18, 18), ColorDepth = ColorDepth.Depth32Bit };
        imgs.Images.Add("all", Theme.Glyph(Theme.GDownload, Theme.Blue, 18));
        foreach (var c in Categories.Names) { var (g, col) = Theme.Cat(c); imgs.Images.Add(c, Theme.Badge(g, col, 18)); }
        imgs.Images.Add("unf", Theme.Glyph(Theme.GPause, Theme.Orange, 18));
        imgs.Images.Add("fin", Theme.Glyph(Theme.GCheck, Theme.Green, 18));
        imgs.Images.Add("q", Theme.Glyph(Theme.GQueuePlay, Theme.Blue, 18));
        imgs.Images.Add("err", Theme.Glyph(Theme.GError, Theme.Red, 18));
        tree.ImageList = imgs;
        var all = new TreeNode("All Downloads") { Tag = "all", ImageKey = "all", SelectedImageKey = "all" };
        foreach (var c in Categories.Names) all.Nodes.Add(new TreeNode(c) { Tag = "cat:" + c, ImageKey = c, SelectedImageKey = c });
        tree.Nodes.Add(all);
        tree.Nodes.Add(new TreeNode("Unfinished") { Tag = "unfinished", ImageKey = "unf", SelectedImageKey = "unf" });
        tree.Nodes.Add(new TreeNode("Finished") { Tag = "finished", ImageKey = "fin", SelectedImageKey = "fin" });
        tree.Nodes.Add(new TreeNode("Errors") { Tag = "error", ImageKey = "err", SelectedImageKey = "err" });
        tree.Nodes.Add(new TreeNode("Queues") { Tag = "queue", ImageKey = "q", SelectedImageKey = "q", Nodes = { new TreeNode("Main download queue") { Tag = "queue", ImageKey = "q", SelectedImageKey = "q" } } });
        all.Expand(); tree.ExpandAll();
        tree.SelectedNode = all;
        tree.AfterSelect += (_, e) => { filter = e.Node.Tag as string ?? "all"; changed = true; };

        // list
        list.Columns.Add("File Name", Theme.S(380)); list.Columns.Add("Size", Theme.S(85), HorizontalAlignment.Right); list.Columns.Add("Status", Theme.S(125));
        list.Columns.Add("Time left", Theme.S(90)); list.Columns.Add("Transfer rate", Theme.S(100)); list.Columns.Add("Last Try Date", Theme.S(150)); list.Columns.Add("Description", Theme.S(240));
        var small = new ImageList { ImageSize = Theme.S(20, 20), ColorDepth = ColorDepth.Depth32Bit };
        foreach (var c in Categories.Names) { var (g, col) = Theme.Cat(c); small.Images.Add(c, Theme.Badge(g, col, 20)); }
        list.SmallImageList = small;
        list.DoubleClick += (_, _) => { var d = Selected().FirstOrDefault(); if (d != null) OpenItem(d); };
        list.KeyDown += (_, e) => { if (e.KeyCode == Keys.Delete) DeleteSelected(); if (e.KeyCode == Keys.Enter) { var d = Selected().FirstOrDefault(); if (d != null) OpenItem(d); } };
        list.ColumnClick += (_, e) => { sortCol = e.Column == sortCol ? -sortCol - 1 : e.Column; changed = true; };
        list.ContextMenuStrip = BuildContextMenu();

        var split = new SplitContainer { Dock = DockStyle.Fill, FixedPanel = FixedPanel.Panel1, BackColor = Color.FromArgb(0xE3, 0xE8, 0xEF) };
        split.Panel1.BackColor = Color.FromArgb(0xF6, 0xF8, 0xFB); split.Panel1.Padding = new Padding(4, 6, 0, 0);
        tree.BackColor = split.Panel1.BackColor;
        split.Panel1.Controls.Add(tree);
        split.Panel2.Controls.Add(list);

        var status = new StatusStrip { BackColor = Theme.Bar, ForeColor = Color.White, SizingGrip = false };
        stSpeed.ForeColor = stActive.ForeColor = Color.White;
        stExt.LinkColor = Color.FromArgb(0xFF, 0xC8, 0x3C); stExt.ActiveLinkColor = Color.White;
        stExt.Click += (_, _) => new ExtensionForm().ShowDialog(this);
        status.Items.AddRange(new ToolStripItem[] { stSpeed, new ToolStripStatusLabel("   "), stActive, stExt });

        Controls.Add(split);
        Controls.Add(tb);
        Controls.Add(menu);
        Controls.Add(header);
        Controls.Add(status);
        MainMenuStrip = menu;
        Load += (_, _) => { try { split.SplitterDistance = Theme.S(205); } catch { } };

        // tray
        tray.Icon = Theme.AppIcon; tray.Text = "MRM Zone IDM"; tray.Visible = true;
        var tm = new ContextMenuStrip();
        tm.Items.Add("Open MRM Zone IDM", null, (_, _) => ShowMain());
        tm.Items.Add("Add new download…", null, (_, _) => { ShowMain(); OpenAdd(null); });
        tm.Items.Add("Stop all downloads", null, (_, _) => Manager.PauseAll());
        tm.Items.Add(new ToolStripSeparator());
        tm.Items.Add("Exit", null, (_, _) => ExitApp());
        tray.ContextMenuStrip = tm;
        tray.DoubleClick += (_, _) => ShowMain();
        tray.BalloonTipClicked += (_, _) => { var a = balloonAction; balloonAction = null; a?.Invoke(); };
    }

    Action balloonAction;

    ContextMenuStrip BuildContextMenu()
    {
        var m = new ContextMenuStrip { Font = Theme.UiFont };
        m.Items.Add("Open", null, (_, _) => Selected().FirstOrDefault()?.Let(OpenFile));
        m.Items.Add("Open folder", null, (_, _) => Selected().FirstOrDefault()?.Let(d => Theme.ShowInFolder(d.Status == DStatus.Completed ? d.FullPath : d.SaveDir + "\\")));
        m.Items.Add("Show progress", null, (_, _) => Selected().FirstOrDefault()?.Let(d => ProgressForm.ShowFor(d.Id)));
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Resume", null, (_, _) => ForSelected(d => Manager.Start(d.Id)));
        m.Items.Add("Stop", null, (_, _) => ForSelected(d => Manager.Pause(d.Id)));
        m.Items.Add("Redownload", null, (_, _) => ForSelected(d => Manager.Redownload(d.Id)));
        m.Items.Add("Add to queue / remove from queue", null, (_, _) => ForSelected(d => Manager.SetInQueue(d.Id, !d.InQueue)));
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Copy address", null, (_, _) => Selected().FirstOrDefault()?.Let(d => { lastClip = d.Url; TrySetClipboard(d.Url); }));
        m.Items.Add("Properties / refresh address…", null, (_, _) => Selected().FirstOrDefault()?.Let(d => new PropertiesForm(d).ShowDialog(this)));
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Delete", null, (_, _) => DeleteSelected());
        return m;
    }

    const int NoSort = int.MinValue;
    int sortCol = NoSort; // >= 0: ascending column; < 0: descending column (-col - 1)

    IEnumerable<DownloadItem> Filtered()
    {
        var l = Manager.Snapshot().AsEnumerable();
        l = filter switch
        {
            "unfinished" => l.Where(x => x.Status != DStatus.Completed),
            "finished" => l.Where(x => x.Status == DStatus.Completed),
            "error" => l.Where(x => x.Status == DStatus.Error),
            "queue" => l.Where(x => x.InQueue),
            var f when f.StartsWith("cat:") => l.Where(x => x.Category == f[4..]),
            _ => l,
        };
        if (!string.IsNullOrWhiteSpace(search)) l = l.Where(x => (x.FileName ?? "").Contains(search, StringComparison.OrdinalIgnoreCase) || (x.Url ?? "").Contains(search, StringComparison.OrdinalIgnoreCase));
        int col = sortCol < 0 ? -sortCol - 1 : sortCol; bool desc = sortCol < 0;
        if (sortCol == NoSort) return l;
        Func<DownloadItem, object> key = col switch { 0 => x => x.FileName, 1 => x => x.TotalSize, 2 => x => x.Status, 5 => x => x.LastTry ?? DateTime.MinValue, _ => x => x.CreatedAt };
        return desc ? l.OrderByDescending(key) : l.OrderBy(key);
    }

    static string StatusText(DownloadItem d) => d.Status switch
    {
        DStatus.Downloading => d.TotalSize > 0 ? $"{d.Downloaded * 100.0 / d.TotalSize:0.00}%" : Fmt.Size(d.Downloaded),
        DStatus.Error => "Error: " + d.Error,
        DStatus.Paused when d.Downloaded > 0 && d.TotalSize > 0 => $"{d.Progress}% (paused)",
        DStatus.Waiting => "Waiting…",
        _ => d.Status.Text(),
    };

    void Refresh2()
    {
        var items = Filtered().ToList();
        var ids = items.Select(i => i.Id).ToList();
        bool same = list.Items.Count == ids.Count && list.Items.Cast<ListViewItem>().Select(r => (long)r.Tag).SequenceEqual(ids);
        // Only rebuild (with BeginUpdate) when rows are added/removed; otherwise just change the
        // texts that differ, so Windows repaints a few cells instead of wiping the whole list.
        if (!same) list.BeginUpdate();
        if (!same)
        {
            var sel = Selected().Select(x => x.Id).ToHashSet();
            list.Items.Clear(); rows.Clear();
            foreach (var d in items)
            {
                var r = new ListViewItem(new string[7]) { Tag = d.Id, ImageKey = d.Category };
                rows[d.Id] = r; list.Items.Add(r);
                if (sel.Contains(d.Id)) r.Selected = true;
            }
        }
        foreach (var d in items)
        {
            var r = rows[d.Id];
            Set(r, 0, d.FileName); Set(r, 1, d.TotalSize > 0 ? Fmt.Size(d.TotalSize) : d.Status == DStatus.Completed ? "" : "");
            Set(r, 2, StatusText(d)); Set(r, 3, d.Status == DStatus.Downloading ? Fmt.Time(d.TimeLeftSec) : "");
            Set(r, 4, d.Status == DStatus.Downloading ? Fmt.Speed(d.Speed) : ""); Set(r, 5, Fmt.Date(d.LastTry ?? d.CreatedAt));
            Set(r, 6, (d.InQueue ? "[Queue] " : "") + (d.Description ?? ""));
            if (r.ImageKey != d.Category) r.ImageKey = d.Category;
            var fc = d.Status switch { DStatus.Error => Theme.Red, DStatus.Completed => Color.FromArgb(0x1B, 0x5E, 0x20), DStatus.Downloading => Theme.Blue, _ => Color.Black };
            if (r.ForeColor != fc) r.ForeColor = fc;
        }
        if (!same) list.EndUpdate();
    }

    static void Set(ListViewItem r, int i, string v) { if (r.SubItems[i].Text != (v ?? "")) r.SubItems[i].Text = v ?? ""; }

    void UpdateStatus()
    {
        var all = Manager.Snapshot();
        bool anyActive = all.Any(x => x.Status == DStatus.Downloading);
        if (anyActive) changed = true;
        var sp = "Speed: " + Fmt.Speed(Manager.TotalSpeed);
        if (stSpeed.Text != sp) stSpeed.Text = sp;
        var act = $"Active: {Manager.RunningCount}    Total: {all.Count}    Completed: {all.Count(x => x.Status == DStatus.Completed)}";
        if (stActive.Text != act) stActive.Text = act;
        var connected = LocalServer.ExtensionConnected;
        var extText = connected ? "✓ Browser extension connected" : "Browser extension not connected — click to install";
        if (stExt.Text != extText) stExt.Text = extText;
        tray.Text = Manager.RunningCount > 0 ? $"MRM Zone IDM — {Manager.RunningCount} downloading, {Fmt.Speed(Manager.TotalSpeed)}" : "MRM Zone IDM";
    }

    List<DownloadItem> Selected() => list.SelectedItems.Cast<ListViewItem>().Select(r => Manager.Get((long)r.Tag)).Where(x => x != null).ToList();

    void ForSelected(Action<DownloadItem> a)
    {
        var s = Selected();
        if (s.Count == 0) { MessageBox.Show(this, "Select one or more downloads in the list first.", "MRM Zone IDM", MessageBoxButtons.OK, MessageBoxIcon.Information); return; }
        s.ForEach(a);
    }

    void OpenItem(DownloadItem d)
    {
        if (d.Status == DStatus.Completed) OpenFile(d);
        else ProgressForm.ShowFor(d.Id);
    }

    void OpenFile(DownloadItem d)
    {
        if (d.Status != DStatus.Completed) { ProgressForm.ShowFor(d.Id); return; }
        if (!File.Exists(d.FullPath)) { MessageBox.Show(this, "File not found. It may have been moved or deleted.\n" + d.FullPath, "MRM Zone IDM"); return; }
        Theme.Open(d.FullPath);
    }

    void DeleteSelected()
    {
        var s = Selected(); if (s.Count == 0) return;
        using var f = new Form { Text = "Delete downloads", Size = new Size(430, 190), FormBorderStyle = FormBorderStyle.FixedDialog, StartPosition = FormStartPosition.CenterParent, MaximizeBox = false, MinimizeBox = false, Font = Theme.UiFont };
        var l = new Label { Text = $"Delete {s.Count} download(s) from the list?", AutoSize = true, Location = new Point(18, 18) };
        var cb = new CheckBox { Text = "Also delete the file(s) from disk", AutoSize = true, Location = new Point(20, 50) };
        var ok = Theme.Btn("Delete", true); ok.Location = new Point(190, 95); ok.DialogResult = DialogResult.OK;
        var cancel = Theme.Btn("Cancel"); cancel.Location = new Point(300, 95); cancel.DialogResult = DialogResult.Cancel;
        f.Controls.AddRange(new Control[] { l, cb, ok, cancel }); f.AcceptButton = ok; f.CancelButton = cancel;
        Theme.Fit(f);
        if (f.ShowDialog(this) == DialogResult.OK) s.ForEach(d => Manager.Delete(d.Id, cb.Checked));
    }

    void ConfirmDeleteCompleted()
    {
        if (MessageBox.Show(this, "Remove all completed downloads from the list?\nThe files stay on your disk.", "Delete completed", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
            Manager.DeleteCompleted();
    }

    public void OpenAdd(string url, string referer = null, string cookies = null, string ua = null, string fileName = null, long size = -1)
    {
        ShowMain();
        if (url != null && Ytdlp.IsVideoSite(url)) { VideoForm.Open(url, null, referer, cookies, ua, null); return; }
        var f = new AddUrlForm(url, referer, cookies, ua, fileName, size);
        f.Show(this); f.Activate();
    }

    void OpenVideoPrompt()
    {
        string clip = null;
        try { clip = Http.ExtractUrl(Clipboard.ContainsText() ? Clipboard.GetText() : ""); } catch { }
        var url = Prompt.Show(this, "Download video", "Paste a YouTube / Facebook / Instagram / TikTok / any video page link:", clip ?? "");
        if (!string.IsNullOrWhiteSpace(url)) VideoForm.Open(url.Trim(), null, null, null, null, null);
    }

    void OnCompleted(DownloadItem d)
    {
        if (App.Settings.ShowCompleteDialog) new CompleteForm(d).Show();
        else { balloonAction = () => OpenFile(d); tray.ShowBalloonTip(5000, "Download complete", string.IsNullOrEmpty(d.FileName) ? "file" : d.FileName, ToolTipIcon.Info); }
    }

    // ---------------- extension requests ----------------
    static string Str(JsonElement j, string n) => j.ValueKind == JsonValueKind.Object && j.TryGetProperty(n, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    static long Lng(JsonElement j, string n)
    {
        if (j.ValueKind != JsonValueKind.Object || !j.TryGetProperty(n, out var v) || v.ValueKind != JsonValueKind.Number) return -1;
        if (v.TryGetInt64(out var l)) return l;
        return v.TryGetDouble(out var dbl) && dbl >= 0 && dbl < long.MaxValue ? (long)dbl : -1; // JS numbers may be floats
    }

    void FromExtensionAdd(JsonElement j)
    {
        var url = Str(j, "url"); if (string.IsNullOrEmpty(url)) return;
        OpenAdd(url, Str(j, "referer") ?? Str(j, "pageUrl"), Str(j, "cookies"), Str(j, "userAgent"), Str(j, "fileName"), Lng(j, "fileSize"));
    }

    void FromExtensionVideo(JsonElement j)
    {
        ShowMain();
        var media = new List<MediaRef>();
        if (j.TryGetProperty("media", out var arr) && arr.ValueKind == JsonValueKind.Array)
            foreach (var m in arr.EnumerateArray())
            {
                if (m.ValueKind != JsonValueKind.Object) continue;
                var u = Str(m, "url"); if (string.IsNullOrEmpty(u)) continue;
                media.Add(new MediaRef { Url = u, Type = Str(m, "type"), Kind = Str(m, "kind") ?? "file", Referer = Str(m, "referer"), Size = Lng(m, "size"), Cookies = Str(m, "cookies") });
            }
        VideoForm.Open(Str(j, "pageUrl"), Str(j, "title"), Str(j, "referer"), Str(j, "cookies"), Str(j, "userAgent"), media);
    }

    void FromExtensionLinks(JsonElement j)
    {
        ShowMain();
        var links = new List<(string url, string text)>();
        if (j.TryGetProperty("links", out var arr) && arr.ValueKind == JsonValueKind.Array)
            foreach (var l in arr.EnumerateArray())
            {
                var u = l.ValueKind == JsonValueKind.String ? l.GetString() : Str(l, "url");
                if (!string.IsNullOrEmpty(u) && u.StartsWith("http")) links.Add((u, l.ValueKind == JsonValueKind.Object ? Str(l, "text") : null));
            }
        new LinksForm(links.Select(x => x.url).Distinct().ToList(), Str(j, "pageUrl")).Show(this);
    }

    // ---------------- window / tray ----------------
    public void ShowMain()
    {
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate(); BringToFront();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!reallyExit && App.Settings.MinimizeToTray && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; Hide();
            if (!trayHintShown) { trayHintShown = true; tray.ShowBalloonTip(3000, "MRM Zone IDM is still running", "Downloads continue in the background. Right-click the tray icon to exit.", ToolTipIcon.Info); }
            return;
        }
        Manager.SaveNow();
        tray.Visible = false;
        base.OnFormClosing(e);
    }
    bool trayHintShown;

    void ExitApp()
    {
        if (Manager.RunningCount > 0 && MessageBox.Show(this, "Downloads are running. Stop them and exit?", "Exit", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        Manager.PauseAll();
        reallyExit = true;
        Thread.Sleep(300);
        Manager.SaveNow();
        tray.Visible = false;
        Application.Exit();
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        if (!App.Settings.ExtensionPromptShown)
        {
            App.Settings.ExtensionPromptShown = true; Store.Save(App.Settings);
            BeginInvoke(() => new ExtensionForm(firstRun: true).ShowDialog(this));
        }
    }

    void ApplyStartup()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            if (App.Settings.StartWithWindows) k?.SetValue("MRMZoneIDM", $"\"{Environment.ProcessPath}\" --tray");
            else k?.DeleteValue("MRMZoneIDM", false);
        }
        catch { }
    }
    public void ReapplyStartup() => ApplyStartup();

    void UpdateEngine()
    {
        var f = new BusyForm("Updating video engine…");
        f.Show(this);
        Task.Run(() =>
        {
            string r; try { r = Tools.Update(); } catch (Exception e) { r = "Update failed: " + e.Message; }
            var ver = Tools.Version(); // runs yt-dlp: keep it off the UI thread
            BeginInvokeSafe(() => { f.Close(); MessageBox.Show(this, r + "\n\nyt-dlp version: " + ver, "Video engine"); });
        });
    }

    void About() => new AboutForm().ShowDialog(this);

    bool TrySetClipboard(string text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        try { Clipboard.SetText(text); return true; }
        catch (Exception e) { MessageBox.Show(this, "Could not copy to the clipboard: " + e.Message, "MRM Zone IDM"); return false; }
    }

    // ---------------- clipboard monitoring ----------------
    [DllImport("user32.dll", SetLastError = true)] static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    const int WM_CLIPBOARDUPDATE = 0x031D;

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e); AddClipboardFormatListener(Handle);
        List<Action> q; lock (pending) { q = pending.ToList(); pending.Clear(); }
        foreach (var a in q) try { BeginInvoke(a); } catch { }
    }
    protected override void OnHandleDestroyed(EventArgs e) { RemoveClipboardFormatListener(Handle); base.OnHandleDestroyed(e); }

    protected override void WndProc(ref Message m)
    {
        base.WndProc(ref m);
        if (m.Msg == WM_CLIPBOARDUPDATE && App.Settings.MonitorClipboard)
        {
            try
            {
                if (!Clipboard.ContainsText()) return;
                var url = Http.ExtractUrl(Clipboard.GetText());
                if (url == null || url == lastClip) return;
                lastClip = url;
                string ext = "";
                try { ext = Path.GetExtension(new Uri(url).AbsolutePath).TrimStart('.').ToLowerInvariant(); } catch { }
                bool video = Ytdlp.IsVideoSite(url) && (url.Contains("watch") || url.Contains("youtu.be") || url.Contains("/shorts/") || url.Contains("/reel") || url.Contains("/video"));
                if (!video && !Categories.AllExts.Contains(ext)) return;
                balloonAction = () => OpenAdd(url);
                tray.ShowBalloonTip(5000, video ? "Download this video?" : "Download this file?", url + "\nClick here to download with MRM Zone IDM", ToolTipIcon.Info);
            }
            catch { }
        }
    }
}

static class Ext
{
    public static void Let<T>(this T t, Action<T> a) where T : class { if (t != null) a(t); }
}
