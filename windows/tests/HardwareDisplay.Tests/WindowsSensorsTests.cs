using System.Text.Json;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Protocol;
using HardwareDisplay.Host.Pairing;
using HardwareDisplay.Windows.Providers;

namespace HardwareDisplay.Tests;

public sealed class WindowsSensorsTests
{
    [Fact]
    public async Task Cpu_and_memory_providers_report_plausible_values()
    {
        await using var collector = new Core.Collection.TelemetryCollector(
            [new CpuTimeProvider(), new MemoryStatusProvider()],
            new Core.Collection.TelemetryCollectorOptions
            {
                TickInterval = TimeSpan.FromMilliseconds(300),
                ProviderTimeout = TimeSpan.FromSeconds(2),
                CatalogRefreshInterval = TimeSpan.FromSeconds(5),
            });

        collector.Start();

        var frame = await Support.Telemetry.WaitForFrame(
            collector,
            f => f.Snapshot.Samples.Any(s => s.SensorId == CpuTimeProvider.Sensor && s.Status == SensorStatus.Ok) &&
                 f.Snapshot.Samples.Count(s => s.Status == SensorStatus.Ok) >= 4,
            timeoutMs: 8000);

        var cpu = frame.Snapshot.Samples.Single(s => s.SensorId == CpuTimeProvider.Sensor);
        var used = frame.Snapshot.Samples.Single(s => s.SensorId == MemoryStatusProvider.UsedSensor);
        var total = frame.Snapshot.Samples.Single(s => s.SensorId == MemoryStatusProvider.TotalSensor);
        var percent = frame.Snapshot.Samples.Single(s => s.SensorId == MemoryStatusProvider.UsedPercentSensor);

        Assert.Equal(SensorStatus.Ok, cpu.Status);
        Assert.InRange(cpu.Value!.Value, 0, 100);
        Assert.NotNull(cpu.AgeMs);

        Assert.Equal(SensorStatus.Ok, total.Status);
        Assert.True(total.Value > 512L * 1024 * 1024, "Физическая память должна быть больше 512 МиБ.");

        Assert.Equal(SensorStatus.Ok, used.Status);
        Assert.InRange(used.Value!.Value, 0, total.Value!.Value);
        Assert.Equal(SensorStatus.Ok, percent.Status);
        Assert.InRange(percent.Value!.Value, 0, 100);
    }

    [Fact]
    public void Cpu_device_label_is_not_empty()
    {
        var provider = new CpuTimeProvider();

        var device = provider.DiscoverDevices().Single();

        Assert.Equal(CpuTimeProvider.Device, device.DeviceId);
        Assert.Equal(DeviceKind.Cpu, device.Kind);
        Assert.False(string.IsNullOrWhiteSpace(device.Label));
    }
}
