using System.Collections.Concurrent;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;

namespace HardwareDisplay.Tests.Support;

internal sealed class FakeProvider : ISensorProvider
{
    private readonly ConcurrentDictionary<string, ProviderValue> _values = new(StringComparer.Ordinal);

    public FakeProvider(string id, params SensorDescriptor[] sensors)
    {
        Id = id;
        SensorList = sensors.ToList();
        DeviceList = sensors
            .Select(s => new DeviceDescriptor(s.DeviceId, "cpu", s.DeviceId))
            .DistinctBy(d => d.DeviceId)
            .ToList();
    }

    public string Id { get; }

    public TimeSpan PollInterval { get; set; } = TimeSpan.FromMilliseconds(20);

    public List<SensorDescriptor> SensorList { get; }

    public List<DeviceDescriptor> DeviceList { get; }

    public int PollCount;

    public Func<IReadOnlyDictionary<string, ProviderValue>>? PollOverride { get; set; }

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices() => DeviceList.ToArray();

    public IReadOnlyList<SensorDescriptor> DiscoverSensors() => SensorList.ToArray();

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        Interlocked.Increment(ref PollCount);

        if (PollOverride is { } custom)
        {
            return custom();
        }

        return _values.ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
    }

    public FakeProvider Set(string sensorId, ProviderValue value)
    {
        _values[sensorId] = value;
        return this;
    }

    public static SensorDescriptor Sensor(string id, string metricKey = "test.value", string unit = SensorUnit.Percent) =>
        new(id, "dev-01", metricKey, id, unit, "test", 1000);
}

internal static class Telemetry
{
    public static readonly TelemetryCollectorOptions Fast = new()
    {
        TickInterval = TimeSpan.FromMilliseconds(25),
        ProviderTimeout = TimeSpan.FromMilliseconds(500),
        CatalogRefreshInterval = TimeSpan.FromMilliseconds(60),
    };

    public static async Task<TelemetryFrame> WaitForFrame(TelemetryCollector collector, Func<TelemetryFrame, bool> match, int timeoutMs = 5000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            var frame = collector.Latest;
            if (frame is not null && match(frame))
            {
                return frame;
            }

            await Task.Delay(10);
        }

        throw new TimeoutException("Подходящий кадр не появился.");
    }

    public static SensorReading Reading(TelemetryFrame frame, string sensorId) =>
        frame.Snapshot.Samples.Single(s => s.SensorId == sensorId);
}

internal sealed class TempDirectory : IDisposable
{
    public TempDirectory()
    {
        Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "hardwaredisplay-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path);
    }

    public string Path { get; }

    public string File(string name) => System.IO.Path.Combine(Path, name);

    public void Dispose()
    {
        try
        {
            Directory.Delete(Path, recursive: true);
        }
        catch (IOException)
        {
        }
    }
}
