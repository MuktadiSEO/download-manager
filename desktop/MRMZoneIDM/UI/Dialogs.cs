using System.Diagnostics;
using System.Text.RegularExpressions;
using Microsoft.Win32;
using MRMZoneIDM.Engine;

namespace MRMZoneIDM.UI;

public static class Prompt
{
    public static string Show(IWin32Window owner, string title, string text, string value)
    {
        using var f = new Form { Text = title, Icon = Theme.AppIcon, Font = Theme.UiFont, ClientSize = new Size(560, 150), FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false, StartPosition = FormStartPosition.CenterParent, BackColor = Color.White };
        var l = new Label { Text = text, AutoSize = true, Location = new Point(16, 16) };
        var tb = new TextBox { Text = value, Width = 528, Location = new Point(16, 44) };
        var ok = Theme.Btn("OK", true, 100); ok.Location = new Point(334, 96); ok.DialogResult = DialogResult.OK;
        var c = Theme.Btn("Cancel", false, 100); c.Location = new Point(444, 96); c.DialogResult = DialogResult.Cancel;
        f.Controls.AddRange(new Control[] { l, tb, ok, c }); f.AcceptButton = ok; f.CancelButton = c;
        return f.ShowDialog(owner) == DialogResult.OK ? tb.Text : null;
    }
}

public class BusyForm : Form
{
    public BusyForm(string text)
    {
        Text = "MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(380, 90); FormBorderStyle = FormBorderStyle.FixedDialog; ControlBox = false; StartPosition = FormStartPosition.CenterParent; BackColor = Color.White;
        Controls.Add(new Label { Text = text, AutoSize = true, Location = new Point(16, 16) });
        Controls.Add(new ProgressBar { Style = ProgressBarStyle.Marquee, Width = 348, Height = 14, Location = new Point(16, 50) });
    }
}

public class OptionsForm : Form
{
    public OptionsForm()
    {
        var s = App.Settings;
        Text = "Options — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(620, 500); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterParent; BackColor = Color.White;
        var tabs = new TabControl { Location = new Point(12, 12), Size = new Size(596, 430) };
        TabPage Page(string t) { var p = new TabPage(t) { BackColor = Color.White, Padding = new Padding(16) }; tabs.TabPages.Add(p); return p; }
        int y;
        CheckBox Cb(TabPage p, string text, bool v) { var c = new CheckBox { Text = text, Checked = v, AutoSize = true, Location = new Point(20, y) }; p.Controls.Add(c); y += 32; return c; }
        NumericUpDown Num(TabPage p, string text, int min, int max, int v) { p.Controls.Add(new Label { Text = text, AutoSize = true, Location = new Point(20, y + 3) }); var n = new NumericUpDown { Minimum = min, Maximum = max, Value = Math.Clamp(v, min, max), Width = 90, Location = new Point(330, y) }; p.Controls.Add(n); y += 36; return n; }

        var g = Page("General"); y = 20;
        var cStart = Cb(g, "Launch MRM Zone IDM when Windows starts (in the tray)", s.StartWithWindows);
        var cTray = Cb(g, "Closing the window keeps MRM Zone IDM running in the tray", s.MinimizeToTray);
        var cClip = Cb(g, "Monitor clipboard for download links", s.MonitorClipboard);
        var cStartDlg = Cb(g, "Show download progress window when a download starts", s.ShowStartDialog);
        var cDone = Cb(g, "Show 'Download complete' dialog", s.ShowCompleteDialog);
        var ext = Theme.Btn("Install browser extension (Chrome / Edge)…", true, 330); ext.Location = new Point(20, y + 10); ext.Click += (_, _) => new ExtensionForm().ShowDialog(this); g.Controls.Add(ext);

        var c = Page("Connection"); y = 20;
        var nConn = Num(c, "Default connections per download (1–32)", 1, 32, s.MaxConnections);
        var nSim = Num(c, "Max. simultaneous downloads", 1, 20, s.MaxSimultaneous);
        var nRet = Num(c, "Retries on network error", 0, 100, s.Retries);
        c.Controls.Add(new Label { Text = "Speed limiter (0 = unlimited), KB/sec", AutoSize = true, Location = new Point(20, y + 3) });
        var nLim = new NumericUpDown { Minimum = 0, Maximum = 10_000_000, Value = Math.Clamp(s.SpeedLimitKb, 0, 10_000_000), Width = 90, Location = new Point(330, y) }; c.Controls.Add(nLim); y += 36;
        c.Controls.Add(new Label { Text = "User-Agent", AutoSize = true, Location = new Point(20, y + 3) }); y += 24;
        var ua = new TextBox { Text = s.UserAgent, Width = 540, Location = new Point(20, y) }; c.Controls.Add(ua);

        var sv = Page("Save to"); y = 20;
        sv.Controls.Add(new Label { Text = "Main download folder (files are sorted into Video, Music, Documents, Compressed, Programs, Images, General):", AutoSize = false, Width = 540, Height = 40, Location = new Point(20, y) }); y += 46;
        var folder = new TextBox { Text = s.BaseFolder, Width = 440, Location = new Point(20, y) }; sv.Controls.Add(folder);
        var br = Theme.Btn("Browse…", false, 90); br.Height = 27; br.Location = new Point(470, y - 2); sv.Controls.Add(br);
        br.Click += (_, _) => { using var d = new FolderBrowserDialog { SelectedPath = folder.Text }; if (d.ShowDialog(this) == DialogResult.OK) folder.Text = d.SelectedPath; };

        var v = Page("Video engine"); y = 20;
        var ver = new Label { Text = "yt-dlp version: checking…", AutoSize = true, Location = new Point(20, y) }; v.Controls.Add(ver); y += 30;
        v.Controls.Add(new Label { Text = "The video engine (yt-dlp + FFmpeg + Deno) downloads YouTube and 1000+ sites in every quality.\nIt updates itself once a day so YouTube keeps working.", AutoSize = false, Width = 540, Height = 50, Location = new Point(20, y), ForeColor = Theme.Gray }); y += 60;
        var up = Theme.Btn("Update video engine now", true, 220); up.Location = new Point(20, y); v.Controls.Add(up);
        // BeginInvoke needs the window handle: start the version check once the form is loaded
        Load += (_, _) => Task.Run(() => { var x = Tools.Version(); try { BeginInvoke(() => ver.Text = "yt-dlp version: " + x); } catch { } });
        up.Click += async (_, _) =>
        {
            up.Enabled = false; ver.Text = "Updating…";
            var r = await Task.Run(() => { try { return Tools.Update() + "   (version " + Tools.Version() + ")"; } catch (Exception e) { return "Failed: " + e.Message; } });
            if (IsDisposed) return;
            ver.Text = r; up.Enabled = true;
        };

        var ok = Theme.Btn("OK", true, 100); ok.Location = new Point(398, 454);
        var cancel = Theme.Btn("Cancel", false, 100); cancel.Location = new Point(508, 454); cancel.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { tabs, ok, cancel }); AcceptButton = ok; CancelButton = cancel;
        ok.Click += (_, _) =>
        {
            s.StartWithWindows = cStart.Checked; s.MinimizeToTray = cTray.Checked; s.MonitorClipboard = cClip.Checked;
            s.ShowStartDialog = cStartDlg.Checked; s.ShowCompleteDialog = cDone.Checked;
            s.MaxConnections = (int)nConn.Value; s.MaxSimultaneous = (int)nSim.Value; s.Retries = (int)nRet.Value;
            s.UserAgent = string.IsNullOrWhiteSpace(ua.Text) ? new Settings().UserAgent : ua.Text.Trim();
            if (!string.IsNullOrWhiteSpace(folder.Text)) s.BaseFolder = folder.Text.Trim();
            Manager.SetGlobalLimit((int)nLim.Value);
            Store.Save(s); App.MainWindow?.ReapplyStartup(); Manager.Schedule();
            Close();
        };
    }
}

public class SchedulerForm : Form
{
    public SchedulerForm()
    {
        var s = App.Settings;
        Text = "Scheduler — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(520, 430); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterParent; BackColor = Color.White;
        Controls.Add(Theme.Header("Scheduler", "Start the main queue automatically (e.g. night packages)"));
        int y = 84;
        var en = new CheckBox { Text = "Start download at", AutoSize = true, Checked = s.SchedEnabled, Location = new Point(20, y + 3) };
        var t1 = new DateTimePicker { Format = DateTimePickerFormat.Time, ShowUpDown = true, Width = 120, Location = new Point(200, y), Value = DateTime.Today.AddMinutes(s.SchedStartMin) };
        y += 40;
        var stop = new CheckBox { Text = "Stop download at", AutoSize = true, Checked = s.SchedStopEnabled, Location = new Point(20, y + 3) };
        var t2 = new DateTimePicker { Format = DateTimePickerFormat.Time, ShowUpDown = true, Width = 120, Location = new Point(200, y), Value = DateTime.Today.AddMinutes(s.SchedStopMin) };
        y += 44;
        Controls.AddRange(new Control[] { en, t1, stop, t2 });
        Controls.Add(new Label { Text = "Days:", AutoSize = true, Location = new Point(20, y) }); y += 24;
        var days = new CheckBox[7]; string[] dn = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        for (int i = 0; i < 7; i++) { days[i] = new CheckBox { Text = dn[i], AutoSize = true, Checked = (s.SchedDays & (1 << i)) != 0, Location = new Point(20 + i * 68, y) }; Controls.Add(days[i]); }
        y += 40;
        var shut = new CheckBox { Text = "Turn off the computer when the queue is finished", AutoSize = true, Checked = s.SchedShutdownWhenDone, Location = new Point(20, y) }; Controls.Add(shut); y += 36;
        int q = Manager.Snapshot().Count(x => x.InQueue && x.Status != DStatus.Completed);
        Controls.Add(new Label { Text = $"Files in main queue: {q}   (use 'Download Later' or right-click → Add to queue)", AutoSize = true, ForeColor = Theme.Gray, Location = new Point(20, y) }); y += 40;
        var startNow = Theme.Btn("Start queue now", false, 140); startNow.Location = new Point(20, y); startNow.Click += (_, _) => { Manager.StartQueue(); Close(); };
        var stopNow = Theme.Btn("Stop queue", false, 110); stopNow.Location = new Point(170, y); stopNow.Click += (_, _) => Manager.StopQueue();
        var ok = Theme.Btn("Apply", true, 100); ok.Location = new Point(300, y);
        var cancel = Theme.Btn("Close", false, 90); cancel.Location = new Point(410, y); cancel.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { startNow, stopNow, ok, cancel });
        ok.Click += (_, _) =>
        {
            s.SchedEnabled = en.Checked; s.SchedStopEnabled = stop.Checked;
            s.SchedStartMin = t1.Value.Hour * 60 + t1.Value.Minute; s.SchedStopMin = t2.Value.Hour * 60 + t2.Value.Minute;
            s.SchedDays = Enumerable.Range(0, 7).Where(i => days[i].Checked).Aggregate(0, (a, i) => a | (1 << i));
            s.SchedShutdownWhenDone = shut.Checked; s.SchedLastRunDay = null;
            Store.Save(s);
            MessageBox.Show(this, s.SchedEnabled ? $"The queue will start at {t1.Value:hh:mm tt} on the selected days.\nKeep MRM Zone IDM running (it can stay in the tray)." : "Scheduler is off.", "Scheduler");
            Close();
        };
    }
}

/// <summary>Select-and-download list for grabbed / extension links.</summary>
public class LinksForm : Form
{
    readonly CheckedListBox box = new() { Dock = DockStyle.Fill, CheckOnClick = true, IntegralHeight = false, HorizontalScrollbar = true };
    readonly TextBox filterBox = new() { Width = 300 };
    readonly List<string> all; readonly string page;

    public LinksForm(List<string> links, string pageUrl)
    {
        all = links; page = pageUrl;
        Text = $"Select files to download ({links.Count}) — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; Size = new Size(760, 560); StartPosition = FormStartPosition.CenterScreen; BackColor = Color.White;
        var top = new Panel { Dock = DockStyle.Top, Height = 44, Padding = new Padding(10) };
        top.Controls.Add(new Label { Text = "Filter (extensions or text):", AutoSize = true, Location = new Point(10, 14) });
        filterBox.Location = new Point(190, 10); top.Controls.Add(filterBox);
        var allB = Theme.Btn("All / none", false, 100); allB.Height = 27; allB.Location = new Point(500, 8); top.Controls.Add(allB);
        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 54 };
        var q = Theme.Btn("Add to queue", false, 130); q.Location = new Point(440, 10);
        var go = Theme.Btn("Download", true, 130); go.Location = new Point(590, 10);
        bottom.Controls.AddRange(new Control[] { q, go });
        Controls.Add(box); Controls.Add(top); Controls.Add(bottom);
        filterBox.TextChanged += (_, _) => Fill();
        allB.Click += (_, _) => { bool v = box.CheckedItems.Count < box.Items.Count; for (int i = 0; i < box.Items.Count; i++) box.SetItemChecked(i, v); };
        go.Click += (_, _) => AddSel(true); q.Click += (_, _) => AddSel(false);
        Fill();
    }

    void Fill()
    {
        var f = filterBox.Text.Trim().ToLowerInvariant();
        var terms = f.Split(new[] { ' ', ',', ';' }, StringSplitOptions.RemoveEmptyEntries);
        box.BeginUpdate(); box.Items.Clear();
        foreach (var l in all.Where(l => terms.Length == 0 || terms.Any(t => l.ToLowerInvariant().Contains(t))))
            box.Items.Add(l, Categories.FromFileName(PathOf(l)) != "General" || all.Count <= 15);
        box.EndUpdate();
    }

    static string PathOf(string u) { try { return new Uri(u).AbsolutePath; } catch { return u; } } // links can be malformed

    void AddSel(bool start)
    {
        var sel = box.CheckedItems.Cast<string>().ToList();
        if (sel.Count == 0) { MessageBox.Show(this, "Tick the files you want first.", Text); return; }
        AddMany(sel, start, page);
        Close();
    }

    public static void AddMany(List<string> urls, bool start, string referer)
    {
        var names = new HashSet<string>();
        foreach (var u in urls)
        {
            var n = Http.FileNameFrom(null, u, null); var b = Path.GetFileNameWithoutExtension(n); var e = Path.GetExtension(n);
            for (int i = 1; !names.Add(n); i++) n = $"{b} ({i}){e}";
            var c = Categories.FromFileName(n);
            Manager.Add(new DownloadItem { Url = u, FileName = n, Category = c, SaveDir = App.Settings.FolderFor(c), Connections = App.Settings.MaxConnections, Referer = referer, InQueue = true }, start);
        }
    }
}

/// <summary>Site grabber: scan a page (optionally linked pages) for files of chosen types.</summary>
public class GrabberForm : Form
{
    public GrabberForm(string url = null)
    {
        Text = "Site Grabber — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(620, 300); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterScreen; BackColor = Color.White;
        Controls.Add(Theme.Header("Site Grabber", "Find every file link on a web page"));
        var u = new TextBox { Width = 580, Location = new Point(20, 104), Text = url ?? "" };
        var types = new TextBox { Width = 580, Location = new Point(20, 160), Text = "mp4 mkv webm avi mp3 m4a pdf zip rar 7z exe msi apk iso jpg png gif doc docx xls xlsx pptx" };
        var deep = new CheckBox { Text = "Also scan pages linked from this page (same site, slower)", AutoSize = true, Location = new Point(20, 196) };
        var st = new Label { AutoSize = true, ForeColor = Theme.Gray, Location = new Point(20, 260) };
        var scan = Theme.Btn("Scan", true, 120); scan.Location = new Point(480, 248);
        Controls.Add(new Label { Text = "Page address", AutoSize = true, Location = new Point(20, 82) });
        Controls.Add(new Label { Text = "File types", AutoSize = true, Location = new Point(20, 138) });
        Controls.AddRange(new Control[] { u, types, deep, st, scan }); AcceptButton = scan;
        scan.Click += async (_, _) =>
        {
            var page = u.Text.Trim(); if (!page.StartsWith("http")) { st.Text = "Enter a valid web address"; return; }
            var exts = types.Text.ToLowerInvariant().Split(new[] { ' ', ',', ';' }, StringSplitOptions.RemoveEmptyEntries).Select(x => x.TrimStart('.')).ToHashSet();
            scan.Enabled = false; st.Text = "Scanning…";
            try
            {
                var found = await Task.Run(() => Grab(page, exts, deep.Checked));
                st.Text = $"{found.Count} file(s) found";
                if (found.Count > 0) { new LinksForm(found, page).Show(); Close(); }
            }
            catch (Exception ex) { st.Text = "Error: " + ex.Message; }
            finally { scan.Enabled = true; }
        };
    }

    static readonly Regex LinkRe = new(@"(?:href|src|data-src|content)\s*=\s*[""']([^""'#]+)[""']", RegexOptions.IgnoreCase);

    static (string, string) Fetch(string url)
    {
        using var req = Http.Request(url, null, null, null);
        using var res = Http.Client.Send(req);
        var type = res.Content.Headers.ContentType?.MediaType ?? "";
        var final = res.RequestMessage?.RequestUri?.ToString() ?? url;
        if (!type.Contains("html") && !type.Contains("text")) return (final, "");
        return (final, res.Content.ReadAsStringAsync().Result);
    }

    static List<string> Links(string baseUrl, string html)
    {
        var o = new List<string>();
        foreach (Match m in LinkRe.Matches(html)) try { o.Add(new Uri(new Uri(baseUrl), m.Groups[1].Value.Trim().Replace("&amp;", "&")).ToString()); } catch { }
        foreach (Match m in Http.UrlRe.Matches(html)) o.Add(m.Value);
        return o.Where(x => x.StartsWith("http")).Distinct().ToList();
    }

    static string Ext(string u) { try { return Path.GetExtension(new Uri(u).AbsolutePath).TrimStart('.').ToLowerInvariant(); } catch { return ""; } }

    static List<string> Grab(string page, HashSet<string> exts, bool deep)
    {
        var (b, html) = Fetch(page);
        var all = Links(b, html);
        var res = all.Where(l => exts.Contains(Ext(l))).ToList();
        if (deep)
        {
            var host = new Uri(b).Host;
            foreach (var p in all.Where(l => { try { return new Uri(l).Host == host && new[] { "", "html", "htm", "php", "asp", "aspx" }.Contains(Ext(l)); } catch { return false; } }).Take(25))
                try { var (b2, h2) = Fetch(p); res.AddRange(Links(b2, h2).Where(l => exts.Contains(Ext(l)))); } catch { }
        }
        return res.Distinct().ToList();
    }
}

public class BatchForm : Form
{
    public BatchForm()
    {
        Text = "Add batch download — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(560, 280); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterParent; BackColor = Color.White;
        Controls.Add(new Label { Text = "Address with * wildcard, e.g. https://site.com/photo*.jpg", AutoSize = true, Location = new Point(20, 20) });
        var u = new TextBox { Width = 520, Location = new Point(20, 44) };
        Controls.Add(new Label { Text = "From", AutoSize = true, Location = new Point(20, 88) });
        var from = new TextBox { Width = 80, Location = new Point(70, 85), Text = "1" };
        Controls.Add(new Label { Text = "To", AutoSize = true, Location = new Point(170, 88) });
        var to = new TextBox { Width = 80, Location = new Point(200, 85), Text = "10" };
        Controls.Add(new Label { Text = "Wildcard size (zero padding)", AutoSize = true, Location = new Point(300, 88) });
        var w = new NumericUpDown { Minimum = 1, Maximum = 8, Value = 1, Width = 60, Location = new Point(480, 85) };
        var prev = new Label { AutoSize = false, Width = 520, Height = 60, Location = new Point(20, 124), ForeColor = Theme.Gray };
        var q = Theme.Btn("Add to queue", false, 130); q.Location = new Point(270, 220);
        var go = Theme.Btn("Download", true, 130); go.Location = new Point(410, 220);
        Controls.AddRange(new Control[] { u, from, to, w, prev, q, go });
        List<string> Expand()
        {
            var p = u.Text.Trim(); if (!p.Contains('*') || !p.StartsWith("http")) return new();
            IEnumerable<string> vals;
            if (int.TryParse(from.Text, out var a) && int.TryParse(to.Text, out var b) && b >= a && b - a <= 10000) vals = Enumerable.Range(a, b - a + 1).Select(i => i.ToString().PadLeft((int)w.Value, '0'));
            else if (from.Text.Length == 1 && to.Text.Length == 1 && char.IsLetter(from.Text[0]) && to.Text[0] >= from.Text[0]) vals = Enumerable.Range(from.Text[0], to.Text[0] - from.Text[0] + 1).Select(c => ((char)c).ToString());
            else return new();
            return vals.Select(v => p.Replace("*", v)).ToList();
        }
        void Upd() { var l = Expand(); prev.Text = l.Count == 0 ? "Enter an address with * and a valid range" : $"{l.Count} files:  {string.Join("   ", l.Take(3))}{(l.Count > 3 ? " …" : "")}"; }
        u.TextChanged += (_, _) => Upd(); from.TextChanged += (_, _) => Upd(); to.TextChanged += (_, _) => Upd(); w.ValueChanged += (_, _) => Upd(); Upd();
        void Add(bool start) { var l = Expand(); if (l.Count == 0) return; LinksForm.AddMany(l, start, null); Close(); }
        go.Click += (_, _) => Add(true); q.Click += (_, _) => Add(false);
    }
}

/// <summary>Properties + "refresh download address" (paste a new link when the old one expired).</summary>
public class PropertiesForm : Form
{
    public PropertiesForm(DownloadItem d)
    {
        Text = "Properties — " + d.FileName; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(600, 420); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterParent; BackColor = Color.White;
        int y = 16;
        TextBox F(string label, string v, bool enabled = true) { Controls.Add(new Label { Text = label, AutoSize = true, ForeColor = Theme.Gray, Location = new Point(20, y) }); var t = new TextBox { Text = v ?? "", Width = 560, Location = new Point(20, y + 20), Enabled = enabled }; Controls.Add(t); y += 54; return t; }
        var url = F("Address (paste a fresh link here if the old one expired)", d.Url);
        var name = F("File name", d.FileName, d.Status != DStatus.Completed);
        var desc = F("Description", d.Description);
        var info = new Label
        {
            AutoSize = false, Width = 560, Height = 140, Location = new Point(20, y), ForeColor = Color.FromArgb(0x33, 0x33, 0x33),
            Text = $"Category: {d.Category}\nSize: {Fmt.Size(d.TotalSize)}    Downloaded: {Fmt.Size(d.Downloaded)}\nStatus: {d.Status.Text()} {d.Error}\nResume support: {(d.Resumable || d.IsYtdlp ? "Yes" : "No")}    Connections: {d.Connections}\nSaved to: {d.SaveDir}\nAdded: {Fmt.Date(d.CreatedAt)}    Last try: {Fmt.Date(d.LastTry)}" + (d.Referer != null ? $"\nReferer: {d.Referer}" : "")
        };
        Controls.Add(info);
        var ok = Theme.Btn("Save", true, 100); ok.Location = new Point(370, 370);
        var c = Theme.Btn("Close", false, 100); c.Location = new Point(480, 370); c.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { ok, c });
        ok.Click += (_, _) =>
        {
            var task = Manager.Task(d.Id);
            if (task != null) { Manager.Pause(d.Id); task.Join(3000); }
            if (url.Text.Trim().StartsWith("http")) d.Url = url.Text.Trim();
            if (d.Status != DStatus.Completed && !string.IsNullOrWhiteSpace(name.Text))
            {
                var oldTemp = d.TempPath;
                d.FileName = Http.Sanitize(name.Text);
                // keep the partial data: the engine only resumes from TempPath, which depends on FileName
                if (oldTemp != d.TempPath && File.Exists(oldTemp)) try { File.Move(oldTemp, d.TempPath, true); } catch { }
            }
            d.Description = desc.Text;
            Manager.Update(d); Close();
        };
    }
}

/// <summary>Guided one-time setup of the Chrome / Edge extension (Chrome does not allow programs to install extensions silently).</summary>
public class ExtensionForm : Form
{
    static string ExtDir => Path.Combine(AppContext.BaseDirectory, "extension");

    static string FindBrowser(string exe)
    {
        foreach (var root in new[] { Registry.CurrentUser, Registry.LocalMachine })
            try
            {
                using var k = root.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\" + exe);
                if (k?.GetValue(null) is string p && File.Exists(p)) return p;
            }
            catch { }
        foreach (var p in new[] {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Google\Chrome\Application\chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\Edge\Application\msedge.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft\Edge\Application\msedge.exe") })
            if (p.EndsWith(exe) && File.Exists(p)) return p;
        return null;
    }

    public ExtensionForm(bool firstRun = false)
    {
        Text = "Install browser extension — MRM Zone IDM"; Icon = Theme.AppIcon; Font = Theme.UiFont; ClientSize = new Size(680, 600); FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false; StartPosition = FormStartPosition.CenterScreen; BackColor = Color.White;
        Controls.Add(Theme.Header("Click to install the browser extension", "Catch downloads and 'Download this video' in Chrome / Edge"));
        const int L = 20, TextX = 60, BtnX = 450, BtnW = 210, Right = 660;
        int y = 80;
        var status = new Label { AutoSize = false, Width = Right - L, Height = 26, Location = new Point(L, y), Font = Theme.Bold };
        Controls.Add(status); y += 32;
        Controls.Add(new Label
        {
            AutoSize = false, Width = Right - L, Height = 44, Location = new Point(L, y), ForeColor = Theme.Gray,
            Text = "Google Chrome only lets you add this kind of extension yourself (it blocks programs from doing it silently, for your safety). It takes 30 seconds, once:"
        });
        y += 54;
        var chrome = FindBrowser("chrome.exe"); var edge = FindBrowser("msedge.exe");
        void Step(string n, string text, string btn, Action a, bool primary = false, int height = 44)
        {
            Controls.Add(new Label { Text = n, Font = new Font("Segoe UI", 16f, FontStyle.Bold), ForeColor = Theme.Orange, AutoSize = true, Location = new Point(L, y - 4) });
            Controls.Add(new Label { Text = text, AutoSize = false, Width = (btn == null ? Right : BtnX - 12) - TextX, Height = height, Location = new Point(TextX, y) });
            if (btn != null) { var bt = Theme.Btn(btn, primary, BtnW); bt.Location = new Point(BtnX, y - 4); bt.Click += (_, _) => a(); Controls.Add(bt); }
            y += height + 12;
        }
        Step("1", "Open your browser's Extensions page.", chrome != null ? "Open Chrome Extensions" : "Open Edge Extensions", () =>
        {
            var exe = chrome ?? edge;
            if (exe == null) { MessageBox.Show(this, "Chrome or Edge was not found. Open your browser and type chrome://extensions in the address bar.", Text); return; }
            try { Process.Start(exe, chrome != null ? "chrome://extensions/" : "edge://extensions/"); } catch { }
            try { Clipboard.SetText(chrome != null ? "chrome://extensions" : "edge://extensions"); } catch { }
        }, true);
        if (chrome != null && edge != null)
        {
            var eb = Theme.Btn("Open Edge Extensions", false, BtnW); eb.Location = new Point(BtnX, y - 14); eb.Click += (_, _) => { try { Process.Start(edge, "edge://extensions/"); } catch { } };
            Controls.Add(eb); y += 34;
        }
        Step("2", "Turn ON \"Developer mode\" (switch at the top-right of the Extensions page; in Edge it is on the left).", null, null);
        Step("3", "Drag the extension folder onto the Extensions page — or click \"Load unpacked\" and select this folder:", "Open extension folder", () =>
        {
            if (!Directory.Exists(ExtDir)) { MessageBox.Show(this, "Extension folder not found: " + ExtDir, Text); return; }
            try { Clipboard.SetText(ExtDir); } catch { }
            try { Process.Start("explorer.exe", $"\"{Path.GetDirectoryName(ExtDir)}\""); } catch { }
        });
        var copy = Theme.Btn("Copy", false, 80); copy.Height = 28; copy.Location = new Point(Right - 80, y - 2);
        copy.Click += (_, _) => { try { Clipboard.SetText(ExtDir); copy.Text = "Copied ✓"; } catch { } };
        var path = new TextBox { Text = ExtDir, ReadOnly = true, Width = copy.Left - 8 - TextX, Location = new Point(TextX, y) };
        Controls.Add(path); Controls.Add(copy); y += 44;
        Step("4", "Done! Pin the MRM Zone icon in the toolbar. Videos now show a \"Download this video\" button and downloads go to MRM Zone IDM.", null, null);
        var close = Theme.Btn(firstRun ? "Later" : "Close", false, 100); close.Location = new Point(Right - 100, ClientSize.Height - 50); close.Click += (_, _) => Close();
        Controls.Add(close);
        var timer = new System.Windows.Forms.Timer { Interval = 1000 };
        void Upd()
        {
            bool ok = LocalServer.ExtensionConnected;
            status.Text = ok ? "✓ Extension connected — you're all set!" : "Extension not connected yet";
            status.ForeColor = ok ? Theme.Green : Theme.Orange;
        }
        timer.Tick += (_, _) => Upd(); timer.Start(); Upd();
        FormClosed += (_, _) => timer.Dispose();
    }
}
