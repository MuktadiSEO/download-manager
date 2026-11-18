using MRMZoneIDM.Engine;
using MRMZoneIDM.UI;

namespace MRMZoneIDM;

public static class App
{
    public static Settings Settings;
    public static string Version => typeof(App).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    public static MainForm MainWindow;

    [STAThread]
    static void Main(string[] args)
    {
        using var mutex = new Mutex(true, "MRMZoneIDM_SingleInstance", out bool first);
        if (!first)
        {
            var url = args.FirstOrDefault(a => a.StartsWith("http", StringComparison.OrdinalIgnoreCase));
            if (url != null) LocalServer.SendToRunning("/add", new { url });
            else if (!args.Contains("--tray")) LocalServer.SendToRunning("/show", null); // autostart duplicate: stay quiet
            return;
        }
        // All forms are laid out in fixed 96-DPI pixel coordinates without AutoScaleMode, so per-monitor
        // awareness would scale fonts but not positions (clipped/overlapping text at 125-150 %).
        // Let Windows scale the GDI output instead (crisp text, correct layout).
        Application.SetHighDpiMode(HighDpiMode.DpiUnawareGdiScaled);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.ThreadException += (_, e) => LogError(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => LogError(e.ExceptionObject as Exception);

        Settings = Store.LoadSettings();
        Manager.Init();
        LocalServer.Start();
        Tools.BackgroundMaintenance();
        MainWindow = new MainForm(args.Contains("--tray"));
        var startUrl = args.FirstOrDefault(a => a.StartsWith("http", StringComparison.OrdinalIgnoreCase));
        if (startUrl != null) MainWindow.Shown += (_, _) => MainWindow.OpenAdd(startUrl);
        Application.Run(MainWindow);
        Manager.SaveNow();
    }

    public static void LogError(Exception e)
    {
        try { Directory.CreateDirectory(Store.Dir); File.AppendAllText(Path.Combine(Store.Dir, "error.log"), $"[{DateTime.Now}] {e}\r\n"); } catch { }
    }
}
