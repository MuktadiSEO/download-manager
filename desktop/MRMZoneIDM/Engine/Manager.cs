using System.Collections.Concurrent;

namespace MRMZoneIDM.Engine;

/// <summary>Central download manager: list, queue, concurrency, persistence, scheduler.</summary>
public static class Manager
{
    public static readonly List<DownloadItem> Items = new();
    static readonly ConcurrentDictionary<long, DownloadTask> Tasks = new();
    public static event Action Changed;
    public static event Action<DownloadItem> Completed;
    public static event Action<DownloadItem> Failed;
    static long nextId = 1;
    static System.Threading.Timer saveTimer, schedTimer;
    static volatile bool dirty;

    public static void Init()
    {
        lock (Items)
        {
            Items.AddRange(Store.LoadDownloads());
            nextId = Items.Count == 0 ? 1 : Items.Max(i => i.Id) + 1;
            foreach (var d in Items) if (d.Status.IsActive()) d.Status = DStatus.Waiting; // resume after restart
        }
        SpeedLimiter.Global.LimitBytesPerSec = App.Settings.SpeedLimitKb * 1024L;
        saveTimer = new System.Threading.Timer(_ => { if (dirty) { dirty = false; try { Store.Save(Items); } catch { } } }, null, 2000, 2000);
        schedTimer = new System.Threading.Timer(_ => SchedulerTick(), null, 10000, 20000);
        Schedule();
    }

    public static List<DownloadItem> Snapshot() { lock (Items) return Items.ToList(); }
    public static DownloadItem Get(long id) { lock (Items) return Items.FirstOrDefault(i => i.Id == id); }
    public static int RunningCount => Tasks.Count;
    public static long TotalSpeed => Tasks.Values.Sum(t => t.Item.Speed);
    public static DownloadTask Task(long id) => Tasks.TryGetValue(id, out var t) ? t : null;

    static void Dirty() { dirty = true; }
    static void Notify() => Changed?.Invoke();
    public static void SaveNow() { try { Store.Save(Items); } catch { } }

    public static DownloadItem Add(DownloadItem d, bool startNow)
    {
        lock (Items) { d.Id = nextId++; Items.Insert(0, d); }
        d.SaveDir ??= App.Settings.FolderFor(d.Category);
        d.Status = startNow ? DStatus.Waiting : d.InQueue ? DStatus.Queued : DStatus.Paused;
        Dirty();
        if (startNow) Schedule();
        Notify();
        return d;
    }

    public static void Start(long id)
    {
        var d = Get(id); if (d == null || d.Status == DStatus.Completed || Tasks.ContainsKey(id)) return;
        d.Status = DStatus.Waiting; d.Error = null; Dirty(); Schedule(); Notify();
    }

    public static void Pause(long id)
    {
        if (Tasks.TryGetValue(id, out var t)) { t.Cancel(); return; }
        var d = Get(id);
        if (d != null && (d.Status == DStatus.Waiting || d.Status == DStatus.Queued)) { d.Status = DStatus.Paused; Dirty(); Notify(); }
    }

    public static void PauseAll()
    {
        foreach (var d in Snapshot()) if (d.Status == DStatus.Waiting) d.Status = DStatus.Paused;
        foreach (var t in Tasks.Values) t.Cancel();
        Dirty(); Notify();
    }

    public static void ResumeAll() { foreach (var d in Snapshot().Where(x => x.Status is DStatus.Paused or DStatus.Error)) Start(d.Id); }
    public static void StartQueue() { foreach (var d in Snapshot().Where(x => x.InQueue && x.Status != DStatus.Completed).OrderBy(x => x.CreatedAt)) Start(d.Id); }
    public static void StopQueue() { foreach (var d in Snapshot().Where(x => x.InQueue)) Pause(d.Id); }

    public static void SetInQueue(long id, bool q)
    {
        var d = Get(id); if (d == null) return;
        d.InQueue = q;
        if (q && d.Status == DStatus.Paused) d.Status = DStatus.Queued;
        if (!q && d.Status == DStatus.Queued) d.Status = DStatus.Paused;
        Dirty(); Notify();
    }

    public static void Delete(long id, bool withFile)
    {
        var d = Get(id); if (d == null) return;
        if (Tasks.TryGetValue(id, out var t)) t.Cancel();
        System.Threading.Tasks.Task.Run(() =>
        {
            t?.Join(5000);
            try { File.Delete(d.TempPath); } catch { }
            if (withFile && d.Status == DStatus.Completed) try { File.Delete(d.FullPath); } catch { }
            if (d.IsYtdlp && d.Status != DStatus.Completed)
                try { foreach (var f in Directory.GetFiles(d.SaveDir, Path.GetFileNameWithoutExtension(d.FileName) + ".*")) if (f.Contains(".part") || f.EndsWith(".ytdl")) File.Delete(f); } catch { }
        });
        lock (Items) Items.RemoveAll(x => x.Id == id);
        Dirty(); Notify();
    }

    public static void DeleteCompleted() { foreach (var d in Snapshot().Where(x => x.Status == DStatus.Completed)) Delete(d.Id, false); }

    public static void Redownload(long id)
    {
        var d = Get(id); if (d == null) return;
        if (Tasks.TryGetValue(id, out var t)) { t.Cancel(); t.Join(3000); }
        try { File.Delete(d.TempPath); } catch { }
        d.Segments = new(); d.Downloaded = 0; d.FinishedAt = null; d.Status = DStatus.Paused;
        Start(id);
    }

    public static void Update(DownloadItem d) { Dirty(); Notify(); }

    public static void SetGlobalLimit(int kb) { App.Settings.SpeedLimitKb = kb; SpeedLimiter.Global.LimitBytesPerSec = kb * 1024L; Store.Save(App.Settings); }
    public static void SetItemLimit(long id, int kb)
    {
        var d = Get(id); if (d == null) return;
        d.SpeedLimitKb = kb;
        if (Tasks.TryGetValue(id, out var t)) t.Limiter.LimitBytesPerSec = kb * 1024L;
        Dirty();
    }

    static readonly object schedLock = new();
    public static void Schedule()
    {
        lock (schedLock)
        {
            int max = Math.Max(1, App.Settings.MaxSimultaneous);
            foreach (var d in Snapshot().Where(x => x.Status == DStatus.Waiting).OrderBy(x => x.CreatedAt))
            {
                if (Tasks.Count >= max) break;
                if (Tasks.ContainsKey(d.Id)) continue;
                var t = new DownloadTask(d, _ => Notify(), OnFinished, _ => Dirty());
                Tasks[d.Id] = t;
                t.Start();
            }
        }
    }

    static void OnFinished(DownloadItem d)
    {
        Tasks.TryRemove(d.Id, out _);
        Dirty();
        if (d.Status == DStatus.Completed) Completed?.Invoke(d);
        else if (d.Status == DStatus.Error) Failed?.Invoke(d);
        Schedule();
        Notify();
        if (App.Settings.SchedShutdownWhenDone && schedulerStarted && Tasks.IsEmpty && !Snapshot().Any(x => x.InQueue && x.Status == DStatus.Waiting))
        {
            schedulerStarted = false;
            SaveNow();
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("shutdown", "/s /t 60 /c \"MRM Zone IDM: downloads finished\"") { CreateNoWindow = true, UseShellExecute = false }); } catch { }
        }
    }

    static bool schedulerStarted;

    static void SchedulerTick()
    {
        var s = App.Settings;
        if (!s.SchedEnabled) return;
        var now = DateTime.Now;
        if ((s.SchedDays & (1 << (int)now.DayOfWeek)) == 0) return;
        int min = now.Hour * 60 + now.Minute;
        var today = now.ToString("yyyy-MM-dd");
        if (min == s.SchedStartMin && s.SchedLastRunDay != today + "S")
        {
            s.SchedLastRunDay = today + "S"; Store.Save(s);
            schedulerStarted = true;
            StartQueue();
        }
        if (s.SchedStopEnabled && min == s.SchedStopMin && s.SchedLastRunDay != today + "E")
        {
            s.SchedLastRunDay = today + "E"; Store.Save(s);
            StopQueue();
        }
    }
}
