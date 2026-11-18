using System.Text.Json;

namespace MRMZoneIDM.Engine;

public class Settings
{
    public int MaxConnections { get; set; } = 8;
    public int MaxSimultaneous { get; set; } = 4;
    public int Retries { get; set; } = 10;
    public int SpeedLimitKb { get; set; } = 0;
    public string BaseFolder { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "MRM Zone");
    public bool ShowStartDialog { get; set; } = true;
    public bool ShowCompleteDialog { get; set; } = true;
    public bool MonitorClipboard { get; set; } = true;
    public bool MinimizeToTray { get; set; } = true;
    public bool StartWithWindows { get; set; } = true;
    public bool ExtensionPromptShown { get; set; }
    public string UserAgent { get; set; } = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    // Scheduler
    public bool SchedEnabled { get; set; }
    public int SchedStartMin { get; set; } = 2 * 60;
    public bool SchedStopEnabled { get; set; }
    public int SchedStopMin { get; set; } = 7 * 60;
    public int SchedDays { get; set; } = 0b1111111; // bit0 = Sunday
    public string SchedLastRunDay { get; set; }
    public bool SchedShutdownWhenDone { get; set; }
    public DateTime ToolsUpdateCheck { get; set; }

    public string FolderFor(string category) => Path.Combine(BaseFolder, category ?? "General");
}

/// <summary>JSON persistence in %AppData%\MRM Zone IDM.</summary>
public static class Store
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MRM Zone IDM");
    static readonly string DownloadsFile = Path.Combine(Dir, "downloads.json");
    static readonly string SettingsFile = Path.Combine(Dir, "settings.json");
    static readonly JsonSerializerOptions Opts = new() { WriteIndented = true };
    static readonly object Lock = new();

    public static Settings LoadSettings()
    {
        try { if (File.Exists(SettingsFile)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsFile)) ?? new Settings(); }
        catch { }
        return new Settings();
    }

    public static void Save(Settings s)
    {
        lock (Lock) { Directory.CreateDirectory(Dir); WriteAtomic(SettingsFile, JsonSerializer.Serialize(s, Opts)); }
    }

    public static List<DownloadItem> LoadDownloads()
    {
        try { if (File.Exists(DownloadsFile)) return JsonSerializer.Deserialize<List<DownloadItem>>(File.ReadAllText(DownloadsFile)) ?? new(); }
        catch
        {
            try { if (File.Exists(DownloadsFile + ".bak")) return JsonSerializer.Deserialize<List<DownloadItem>>(File.ReadAllText(DownloadsFile + ".bak")) ?? new(); } catch { }
        }
        return new();
    }

    public static void Save(List<DownloadItem> items)
    {
        lock (Lock)
        {
            Directory.CreateDirectory(Dir);
            string json;
            lock (items) json = JsonSerializer.Serialize(items, Opts);
            if (File.Exists(DownloadsFile)) try { File.Copy(DownloadsFile, DownloadsFile + ".bak", true); } catch { }
            WriteAtomic(DownloadsFile, json);
        }
    }

    static void WriteAtomic(string path, string text)
    {
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, text);
        File.Move(tmp, path, true);
    }
}
