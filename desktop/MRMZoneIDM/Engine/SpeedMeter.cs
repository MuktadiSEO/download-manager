namespace MRMZoneIDM.Engine;

/// <summary>Steady transfer rate: bytes over a sliding ~3 second window (like IDM), not the last instant.</summary>
public class SpeedMeter
{
    readonly Queue<(long t, long b)> samples = new();
    readonly int windowMs;
    long last;
    public SpeedMeter(int windowMs = 3000) { this.windowMs = windowMs; }

    public long Add(long totalBytes)
    {
        var now = Environment.TickCount64;
        samples.Enqueue((now, totalBytes));
        while (samples.Count > 2 && now - samples.Peek().t > windowMs) samples.Dequeue();
        var (t0, b0) = samples.Peek();
        var dt = now - t0;
        if (dt >= 300) last = Math.Max(0, (totalBytes - b0) * 1000 / dt);
        return last;
    }
}
