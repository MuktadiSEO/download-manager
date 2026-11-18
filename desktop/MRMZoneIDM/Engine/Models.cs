using System.Text.Json.Serialization;

namespace MRMZoneIDM.Engine;

public enum DStatus { Queued, Waiting, Connecting, Downloading, Paused, Completed, Error, Merging }

public static class DStatusExt
{
    public static bool IsActive(this DStatus s) => s is DStatus.Waiting or DStatus.Connecting or DStatus.Downloading or DStatus.Merging;
    public static string Text(this DStatus s) => s switch
    {
        DStatus.Queued => "Queued", DStatus.Waiting => "Waiting", DStatus.Connecting => "Connecting…",
        DStatus.Downloading => "Receiving data…", DStatus.Paused => "Paused", DStatus.Completed => "Complete",
        DStatus.Error => "Error", DStatus.Merging => "Merging / saving…", _ => s.ToString()
    };
}

/// <summary>One byte range downloaded by one connection. End is inclusive; long.MaxValue = unknown length.</summary>
public class Segment
{
    public long Start { get; set; }
    public long End { get; set; }
    public long Done { get; set; }
    public const long Unknown = long.MaxValue;
    [JsonIgnore] public long Position => Start + Done;
    [JsonIgnore] public long Remaining => End == Unknown ? long.MaxValue : End - Position + 1;
    [JsonIgnore] public bool Finished => Position > End;
    public Segment Copy() => new() { Start = Start, End = End, Done = Done };
}

public class DownloadItem
{
    public long Id { get; set; }
    public string Url { get; set; }
    public string FileName { get; set; }
    public string Category { get; set; } = "General";
    public string SaveDir { get; set; }
    public long TotalSize { get; set; } = -1;
    public long Downloaded { get; set; }
    public DStatus Status { get; set; } = DStatus.Paused;
    public int Connections { get; set; } = 8;
    public bool Resumable { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime? FinishedAt { get; set; }
    public DateTime? LastTry { get; set; }
    public string Error { get; set; }
    public List<Segment> Segments { get; set; } = new();
    public string Referer { get; set; }
    public string UserAgent { get; set; }
    public string Cookies { get; set; }
    public bool InQueue { get; set; }
    public string Description { get; set; }
    public int SpeedLimitKb { get; set; }
    /// <summary>null = direct HTTP, "ytdlp" = video site / stream handled by yt-dlp</summary>
    public string Kind { get; set; }
    public string FormatSpec { get; set; }
    public string Extra { get; set; }

    [JsonIgnore] public long Speed { get; set; }
    [JsonIgnore] public int ActiveConnections { get; set; }
    [JsonIgnore] public List<Segment> LiveSegments { get; set; } = new();
    [JsonIgnore] public bool IsYtdlp => Kind == "ytdlp";
    [JsonIgnore] public string FullPath => Path.Combine(SaveDir ?? "", FileName ?? "file");
    [JsonIgnore] public string TempPath => FullPath + ".mrmpart";
    [JsonIgnore] public int Progress => TotalSize > 0 ? (int)Math.Clamp(Downloaded * 100 / TotalSize, 0, 100) : (Status == DStatus.Completed ? 100 : 0);
    [JsonIgnore] public long TimeLeftSec => Speed > 0 && TotalSize > 0 ? (TotalSize - Downloaded) / Speed : -1;
}

public static class Categories
{
    public static readonly string[] Names = { "Video", "Music", "Documents", "Compressed", "Programs", "Images", "General" };
    static readonly Dictionary<string, string[]> Exts = new()
    {
        ["Video"] = new[] { "mp4","mkv","avi","mov","wmv","flv","webm","3gp","m4v","mpg","mpeg","ts","vob","ogv","rm","rmvb" },
        ["Music"] = new[] { "mp3","m4a","aac","wav","flac","ogg","wma","opus","amr","mid","midi","aif","aiff","ra" },
        ["Documents"] = new[] { "pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","odt","ods","odp","epub","csv","pps","mobi" },
        ["Compressed"] = new[] { "zip","rar","7z","tar","gz","bz2","xz","tgz","iso","img","arj","lzh","sit","sitx","z","ace","r00","r01" },
        ["Programs"] = new[] { "exe","msi","apk","xapk","dmg","deb","rpm","bin","jar","appx","msix","msu","bat","cmd" },
        ["Images"] = new[] { "jpg","jpeg","png","gif","webp","bmp","svg","ico","tif","tiff","heic" },
    };
    public static string FromFileName(string name)
    {
        var ext = Path.GetExtension(name ?? "").TrimStart('.').ToLowerInvariant();
        foreach (var kv in Exts) if (kv.Value.Contains(ext)) return kv.Key;
        return "General";
    }
    public static HashSet<string> AllExts => Exts.Values.SelectMany(x => x).ToHashSet();
}
