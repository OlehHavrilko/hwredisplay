using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Model;

namespace HardwareDisplay.Core.Protocol;

public static class FrameMapper
{
    public static CatalogMessage ToCatalogMessage(Catalog catalog) =>
        new(
            ProtocolJson.Version,
            "catalog",
            catalog.Revision,
            catalog.Devices.Select(d => new DeviceDto(d.DeviceId, d.Kind, d.Label)).ToArray(),
            catalog.Sensors
                .Select(s => new SensorDto(s.SensorId, s.DeviceId, s.MetricKey, s.Label, s.Unit, s.Source, s.SamplePeriodMs))
                .ToArray());

    public static SnapshotMessage ToSnapshotMessage(Snapshot snapshot) =>
        new(
            ProtocolJson.Version,
            "snapshot",
            snapshot.StreamEpoch.ToString(),
            snapshot.Revision,
            snapshot.Seq,
            snapshot.CapturedAtUtc,
            snapshot.MonotonicMs,
            snapshot.Samples
                .Select(s => new SampleDto(s.SensorId, s.Status, s.Value, s.Reason, s.AgeMs))
                .ToArray());
}
