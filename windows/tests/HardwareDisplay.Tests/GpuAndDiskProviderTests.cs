using HardwareDisplay.Core.Model;
using HardwareDisplay.Windows.Providers;

namespace HardwareDisplay.Tests;

public sealed class GpuAndDiskProviderTests
{
    [Fact]
    public void Dxgi_inventory_reports_adapter_with_real_memory()
    {
        var adapters = GpuInventory.Enumerate();

        if (adapters.Count == 0)
        {
            return;
        }

        foreach (var adapter in adapters)
        {
            Assert.False(string.IsNullOrWhiteSpace(adapter.Label));
            Assert.NotEqual(0UL, adapter.Luid);
            Assert.StartsWith("gpu-", adapter.DeviceId, StringComparison.Ordinal);
            Assert.True(adapter.DedicatedVideoMemory > 0, "Выделенная видеопамять должна быть известна.");
        }
    }

    [Fact]
    public void Gpu_providers_expose_busy_and_memory_sensors()
    {
        using var engine = new GpuEngineProvider();
        using var memory = new GpuMemoryProvider();

        var devices = engine.DiscoverDevices();
        if (devices.Count == 0)
        {
            return;
        }

        var busy = engine.DiscoverSensors().Select(s => s.SensorId).ToArray();
        var vram = memory.DiscoverSensors().Select(s => s.SensorId).ToArray();

        Assert.All(devices, d => Assert.Equal(DeviceKind.Gpu, d.Kind));
        Assert.Contains(busy, id => id.EndsWith("-busy", StringComparison.Ordinal));
        Assert.Contains(vram, id => id.EndsWith("-vram-used", StringComparison.Ordinal));
        Assert.Contains(vram, id => id.EndsWith("-vram-total", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Gpu_and_disk_providers_produce_values_after_warmup()
    {
        await using var collector = new Core.Collection.TelemetryCollector(
            [new GpuEngineProvider(), new GpuMemoryProvider(), new PhysicalDiskProvider()],
            new Core.Collection.TelemetryCollectorOptions
            {
                TickInterval = TimeSpan.FromMilliseconds(400),
                ProviderTimeout = TimeSpan.FromSeconds(3),
                CatalogRefreshInterval = TimeSpan.FromSeconds(5),
            });

        collector.Start();

        var frame = await Support.Telemetry.WaitForFrame(
            collector,
            f => f.Snapshot.Samples.Any(s => s.SensorId.EndsWith("-vram-total", StringComparison.Ordinal) && s.Status == SensorStatus.Ok) &&
                 f.Snapshot.Samples.Any(s => s.SensorId.EndsWith("-read", StringComparison.Ordinal) && s.Status == SensorStatus.Ok),
            timeoutMs: 12000);

        var vramTotal = frame.Snapshot.Samples.First(s => s.SensorId.EndsWith("-vram-total", StringComparison.Ordinal));
        Assert.Equal(SensorStatus.Ok, vramTotal.Status);
        Assert.True(vramTotal.Value > 1024L * 1024 * 1024, "Видеопамять должна быть больше 1 ГиБ.");

        var read = frame.Snapshot.Samples.First(s => s.SensorId.EndsWith("-read", StringComparison.Ordinal));
        Assert.Equal(SensorStatus.Ok, read.Status);
        Assert.True(read.Value >= 0);

        var diskDevices = frame.Catalog.Devices.Where(d => d.Kind == DeviceKind.Storage).ToArray();
        Assert.NotEmpty(diskDevices);
        Assert.All(diskDevices, d => Assert.False(string.IsNullOrWhiteSpace(d.Label)));

        var diskBusy = frame.Snapshot.Samples.First(s => s.SensorId.EndsWith("-busy", StringComparison.Ordinal) &&
                                                         frame.Catalog.Devices.Any(d => s.SensorId.StartsWith(d.DeviceId + "-", StringComparison.Ordinal)));
        if (diskBusy.Value is { } busyPercent)
        {
            Assert.InRange(busyPercent, 0, 100);
        }
    }
}
