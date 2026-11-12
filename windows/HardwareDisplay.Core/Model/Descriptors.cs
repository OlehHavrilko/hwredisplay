namespace HardwareDisplay.Core.Model;

public sealed record DeviceDescriptor(string DeviceId, string Kind, string Label);

public sealed record SensorDescriptor(
    string SensorId,
    string DeviceId,
    string MetricKey,
    string Label,
    string Unit,
    string Source,
    int SamplePeriodMs);

public sealed record SensorReading(
    string SensorId,
    string Status,
    double? Value,
    string? Reason,
    long? AgeMs);

public sealed record Catalog(long Revision, IReadOnlyList<DeviceDescriptor> Devices, IReadOnlyList<SensorDescriptor> Sensors);

public sealed record Snapshot(
    Guid StreamEpoch,
    long Revision,
    long Seq,
    DateTimeOffset CapturedAtUtc,
    long MonotonicMs,
    IReadOnlyList<SensorReading> Samples);
