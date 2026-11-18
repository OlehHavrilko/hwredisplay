using HardwareDisplay.Core.Model;
using HardwareDisplay.Windows.Providers;

namespace HardwareDisplay.Tests;

public sealed class HardwareMonitorProviderTests
{
    [Fact]
    public void Provider_opens_and_reports_temperature_or_reason()
    {
        using var provider = new HardwareMonitorProvider();

        var devices = provider.DiscoverDevices();

        if (devices.Count == 0)
        {
            Assert.NotNull(provider.FailureReason);
            return;
        }

        Assert.All(devices, d => Assert.False(string.IsNullOrWhiteSpace(d.Label)));
        Assert.All(devices, d => Assert.Contains(d.Kind, new[]
        {
            DeviceKind.Cpu,
            DeviceKind.Gpu,
            DeviceKind.Motherboard,
            DeviceKind.Storage,
        }));

        var sensors = provider.DiscoverSensors();
        Assert.NotEmpty(sensors);
        Assert.All(sensors, s => Assert.Contains(s.Unit, new[] { SensorUnit.Celsius, SensorUnit.Rpm }));
        Assert.All(sensors, s => Assert.False(string.IsNullOrWhiteSpace(s.MetricKey)));
    }

    [Fact]
    public void Zero_celsius_is_not_reported_as_measurement()
    {
        using var provider = new HardwareMonitorProvider();

        if (provider.DiscoverDevices().Count == 0)
        {
            return;
        }

        var temperatures = provider.DiscoverSensors()
            .Where(s => s.Unit == SensorUnit.Celsius)
            .Select(s => s.SensorId)
            .ToHashSet(StringComparer.Ordinal);

        if (temperatures.Count == 0)
        {
            return;
        }

        provider.Poll();
        Thread.Sleep(300);
        var values = provider.Poll();

        foreach (var sensorId in temperatures)
        {
            if (!values.TryGetValue(sensorId, out var value) || value.Status != SensorStatus.Ok)
            {
                continue;
            }

            Assert.True(value.Value > 0, $"Температура {sensorId} не должна быть нулём.");
        }
    }
}
