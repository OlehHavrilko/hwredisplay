using HardwareDisplay.Core.Collection;

namespace HardwareDisplay.Core.Protocol;

public static class FrameMapper
{
    public static CatalogMessage ToCatalogMessage(TelemetryFrame frame) =>
        new(
            ProtocolJson.Version,
            "catalog",
            frame.Catalog.Revision,
            frame.Catalog.Devices.Select(d => new DeviceDto(d.DeviceId, d.Kind, d.Label)).ToArray(),
            frame.Catalog.Sensors
                .Select(s => new SensorDto(s.SensorId, s.DeviceId, s.MetricKey, s.Label, s.Unit, s.Source, s.SamplePeriodMs))
                .ToArray());

    public static SnapshotMessage ToSnapshotMessage(TelemetryFrame frame) =>
        new(
            ProtocolJson.Version,
            "snapshot",
            frame.Snapshot.StreamEpoch.ToString(),
            frame.Snapshot.Revision,
            frame.Snapshot.Seq,
            frame.Snapshot.CapturedAtUtc,
            frame.Snapshot.MonotonicMs,
            frame.Snapshot.Samples
                .Select(s => new SampleDto(s.SensorId, s.Status, s.Value, s.Reason, s.AgeMs))
                .ToArray());
}
