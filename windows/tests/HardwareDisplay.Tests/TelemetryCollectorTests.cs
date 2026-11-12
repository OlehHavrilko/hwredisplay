using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;
using HardwareDisplay.Tests.Support;

namespace HardwareDisplay.Tests;

public sealed class TelemetryCollectorTests
{
    [Fact]
    public async Task Publishes_ok_reading_with_age_and_growing_seq()
    {
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
            .Set("sensor-1", ProviderValue.Ok(12.5));

        await using var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var first = await Telemetry.WaitForFrame(collector, f => Telemetry.Reading(f, "sensor-1").Status == SensorStatus.Ok);
        var second = await Telemetry.WaitForFrame(collector, f => f.Snapshot.Seq > first.Snapshot.Seq);

        var reading = Telemetry.Reading(second, "sensor-1");
        Assert.Equal(12.5, reading.Value);
        Assert.NotNull(reading.AgeMs);
        Assert.InRange(reading.AgeMs!.Value, 0, second.Snapshot.MonotonicMs);
        Assert.Equal(first.Snapshot.StreamEpoch, second.Snapshot.StreamEpoch);
        Assert.Equal(1, second.Catalog.Revision);
    }

    [Fact]
    public async Task Reports_waiting_until_provider_returns_first_value()
    {
        var poll = 0;
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
        {
            PollOverride = () =>
            {
                var call = Interlocked.Increment(ref poll);
                return new Dictionary<string, ProviderValue>(StringComparer.Ordinal)
                {
                    ["sensor-1"] = call == 1 ? ProviderValue.Waiting() : ProviderValue.Ok(7),
                };
            },
        };

        await using var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var frame = await Telemetry.WaitForFrame(collector, f => Telemetry.Reading(f, "sensor-1").Status == SensorStatus.Ok);
        Assert.Equal(7, Telemetry.Reading(frame, "sensor-1").Value);
    }

    [Fact]
    public async Task Marks_group_error_when_provider_throws()
    {
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
        {
            PollOverride = () => throw new InvalidOperationException("провайдер недоступен"),
        };

        await using var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var frame = await Telemetry.WaitForFrame(collector, f => Telemetry.Reading(f, "sensor-1").Status == SensorStatus.Error);
        var reading = Telemetry.Reading(frame, "sensor-1");

        Assert.Null(reading.Value);
        Assert.Equal(SensorReason.ReadFailed, reading.Reason);
    }

    [Fact]
    public async Task Marks_stale_when_provider_exceeds_deadline()
    {
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
        {
            PollOverride = () =>
            {
                Thread.Sleep(300);
                return new Dictionary<string, ProviderValue>(StringComparer.Ordinal)
                {
                    ["sensor-1"] = ProviderValue.Ok(1),
                };
            },
        };

        var options = new TelemetryCollectorOptions
        {
            TickInterval = TimeSpan.FromMilliseconds(25),
            ProviderTimeout = TimeSpan.FromMilliseconds(60),
            CatalogRefreshInterval = TimeSpan.FromMilliseconds(60),
        };

        await using var collector = new TelemetryCollector([provider], options);
        collector.Start();

        var frame = await Telemetry.WaitForFrame(collector, f => Telemetry.Reading(f, "sensor-1").Status == SensorStatus.Stale);
        Assert.Equal(SensorReason.TimedOut, Telemetry.Reading(frame, "sensor-1").Reason);
    }

    [Fact]
    public async Task Grows_catalog_revision_when_sensor_set_changes()
    {
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
            .Set("sensor-1", ProviderValue.Ok(1));

        await using var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var initial = await Telemetry.WaitForFrame(collector, f => f.Snapshot.Samples.Count == 1);
        Assert.Equal(1, initial.Catalog.Revision);

        provider.SensorList.Add(FakeProvider.Sensor("sensor-2"));
        provider.Set("sensor-2", ProviderValue.Ok(2));

        var grown = await Telemetry.WaitForFrame(collector, f => f.Snapshot.Samples.Count == 2 && f.Catalog.Revision == 2);

        Assert.Contains(grown.Catalog.Sensors, s => s.SensorId == "sensor-2");
        Assert.Contains(grown.Snapshot.Samples, s => s.SensorId == "sensor-2");
        Assert.All(grown.Snapshot.Samples, s => Assert.Equal(grown.Catalog.Revision, grown.Snapshot.Revision));
    }

    [Fact]
    public async Task Keeps_zero_as_real_value()
    {
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
            .Set("sensor-1", ProviderValue.Ok(0));

        await using var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var frame = await Telemetry.WaitForFrame(collector, f => Telemetry.Reading(f, "sensor-1").Value is not null);
        var reading = Telemetry.Reading(frame, "sensor-1");

        Assert.Equal(SensorStatus.Ok, reading.Status);
        Assert.Equal(0, reading.Value);
    }
}
