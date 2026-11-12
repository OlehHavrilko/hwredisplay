using System.Runtime.Versioning;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;
using HardwareDisplay.Windows.Interop;

namespace HardwareDisplay.Windows.Providers;

[SupportedOSPlatform("windows")]
public sealed class MemoryStatusProvider : ISensorProvider
{
    public const string Device = "memory-01";
    public const string UsedSensor = "memory-01-used";
    public const string TotalSensor = "memory-01-total";
    public const string UsedPercentSensor = "memory-01-used-percent";

    public string Id => "win32.GlobalMemoryStatusEx";

    public TimeSpan PollInterval => TimeSpan.FromSeconds(1);

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices() =>
        [new DeviceDescriptor(Device, DeviceKind.Memory, "Память")];

    public IReadOnlyList<SensorDescriptor> DiscoverSensors() =>
        [
            new SensorDescriptor(UsedSensor, Device, "memory.used.bytes", "Занято памяти", SensorUnit.Bytes, "win32.GlobalMemoryStatusEx", 1000),
            new SensorDescriptor(TotalSensor, Device, "memory.total.bytes", "Всего памяти", SensorUnit.Bytes, "win32.GlobalMemoryStatusEx", 1000),
            new SensorDescriptor(UsedPercentSensor, Device, "memory.used.percent", "Использование памяти", SensorUnit.Percent, "win32.GlobalMemoryStatusEx", 1000)
        ];

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        var status = new NativeMethods.MemoryStatusEx { Length = (uint)System.Runtime.InteropServices.Marshal.SizeOf<NativeMethods.MemoryStatusEx>() };

        if (!NativeMethods.GlobalMemoryStatusEx(ref status) || status.TotalPhys == 0)
        {
            return new Dictionary<string, ProviderValue>(StringComparer.Ordinal)
            {
                [UsedSensor] = ProviderValue.Error(SensorReason.ReadFailed),
                [TotalSensor] = ProviderValue.Error(SensorReason.ReadFailed),
                [UsedPercentSensor] = ProviderValue.Error(SensorReason.ReadFailed),
            };
        }

        var total = status.TotalPhys;
        var used = total > status.AvailPhys ? total - status.AvailPhys : 0;
        var percent = 100.0 * used / total;

        return new Dictionary<string, ProviderValue>(StringComparer.Ordinal)
        {
            [UsedSensor] = ProviderValue.Ok(used),
            [TotalSensor] = ProviderValue.Ok(total),
            [UsedPercentSensor] = ProviderValue.Ok(percent),
        };
    }
}
