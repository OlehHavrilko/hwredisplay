using HardwareDisplay.Core.Model;

namespace HardwareDisplay.Core.Providers;

public readonly record struct ProviderValue(string Status, double? Value, string? Reason)
{
    public static ProviderValue Ok(double value) => new(SensorStatus.Ok, value, null);

    public static ProviderValue Waiting(string reason = SensorReason.Warmup) => new(SensorStatus.Waiting, null, reason);

    public static ProviderValue Unavailable(string reason) => new(SensorStatus.Unavailable, null, reason);

    public static ProviderValue Error(string reason = SensorReason.ReadFailed) => new(SensorStatus.Error, null, reason);
}

public interface ISensorProvider
{
    string Id { get; }

    TimeSpan PollInterval { get; }

    IReadOnlyList<DeviceDescriptor> DiscoverDevices();

    IReadOnlyList<SensorDescriptor> DiscoverSensors();

    IReadOnlyDictionary<string, ProviderValue> Poll();
}
