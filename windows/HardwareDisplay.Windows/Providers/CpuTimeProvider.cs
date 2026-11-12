using System.Runtime.Versioning;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;
using HardwareDisplay.Windows.Interop;
using Microsoft.Win32;

namespace HardwareDisplay.Windows.Providers;

[SupportedOSPlatform("windows")]
public sealed class CpuTimeProvider : ISensorProvider
{
    public const string Device = "cpu-01";
    public const string Sensor = "cpu-01-time";

    private CpuTimes? _previous;

    public string Id => "win32.GetSystemTimes";

    public TimeSpan PollInterval => TimeSpan.FromSeconds(1);

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices() =>
        [new DeviceDescriptor(Device, DeviceKind.Cpu, CpuName())];

    public IReadOnlyList<SensorDescriptor> DiscoverSensors() =>
        [
            new SensorDescriptor(
                Sensor,
                Device,
                "cpu.time_busy.percent",
                "Занятость CPU по времени",
                SensorUnit.Percent,
                "win32.GetSystemTimes",
                1000)
        ];

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        var values = new Dictionary<string, ProviderValue>(StringComparer.Ordinal);

        if (!NativeMethods.GetSystemTimes(out var idle, out var kernel, out var user))
        {
            values[Sensor] = ProviderValue.Error(SensorReason.ReadFailed);
            return values;
        }

        var current = new CpuTimes(idle.ToUInt64(), kernel.ToUInt64(), user.ToUInt64());
        var previous = _previous;
        _previous = current;

        if (previous is null)
        {
            values[Sensor] = ProviderValue.Waiting();
            return values;
        }

        values[Sensor] = CpuBusyMath.TryCompute(previous.Value, current, out var busy)
            ? ProviderValue.Ok(busy)
            : ProviderValue.Waiting();

        return values;
    }

    private static string CpuName()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            if (key?.GetValue("ProcessorNameString") is string name && !string.IsNullOrWhiteSpace(name))
            {
                return name.Trim();
            }
        }
        catch (Exception)
        {
        }

        return "Процессор";
    }
}
