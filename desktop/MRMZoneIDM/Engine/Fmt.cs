using System.Globalization;
namespace MRMZoneIDM.Engine;

public static class Fmt
{
    public static string Size(long b)
    {
        if (b < 0) return "Unknown";
        if (b < 1024) return b + " B";
        string[] u = { "KB", "MB", "GB", "TB" };
        double v = b / 1024.0; int i = 0;
        while (v >= 1024 && i < u.Length - 1) { v /= 1024; i++; }
        return v.ToString(v >= 100 ? "0" : "0.00", CultureInfo.InvariantCulture) + " " + u[i];
    }
    public static string Speed(long b) => Size(b) + "/sec";
    public static string Time(long sec)
    {
        if (sec < 0) return "";
        long h = sec / 3600, m = sec % 3600 / 60, s = sec % 60;
        return h > 0 ? $"{h} hr {m} min" : m > 0 ? $"{m} min {s} sec" : $"{s} sec";
    }
    public static string Date(DateTime? d) => d?.ToString("MMM dd, yyyy  hh:mm tt", CultureInfo.InvariantCulture) ?? "";
}
