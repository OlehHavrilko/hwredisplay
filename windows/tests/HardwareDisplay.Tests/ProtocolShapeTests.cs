using System.Text.Json;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Protocol;
using HardwareDisplay.Host.Pairing;

namespace HardwareDisplay.Tests;

/// <summary>
/// Проверки формы сообщений из docs/PROTOCOL.md: имена полей, единицы и отсутствие
/// лишних значений у недоступного датчика.
/// </summary>
public sealed class ProtocolShapeTests
{
    [Fact]
    public void Invite_payload_matches_documented_fields()
    {
        var payload = InvitePayload.Create(
            "1482a7c6-1d42-40af-869e-6e09ceab6de2",
            "192.168.1.20",
            18443,
            new string('a', 64),
            new string('b', 32));

        using var json = JsonDocument.Parse(payload.ToJson());
        var root = json.RootElement;

        Assert.Equal(1, root.GetProperty("v").GetInt32());
        Assert.Equal("1482a7c6-1d42-40af-869e-6e09ceab6de2", root.GetProperty("hostId").GetString());
        Assert.Equal("192.168.1.20", root.GetProperty("ip").GetString());
        Assert.Equal(18443, root.GetProperty("port").GetInt32());
        Assert.Equal(64, root.GetProperty("certSha256").GetString()!.Length);
        Assert.Equal(32, root.GetProperty("invite").GetString()!.Length);
    }

    [Fact]
    public void Hello_message_carries_protocol_version_and_period()
    {
        var epoch = Guid.NewGuid();

        var json = JsonSerializer.Serialize(
            HelloMessage.Create("1482a7c6-1d42-40af-869e-6e09ceab6de2", epoch, 1000),
            ProtocolJson.Options);

        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;

        Assert.Equal(1, root.GetProperty("v").GetInt32());
        Assert.Equal("hello", root.GetProperty("type").GetString());
        Assert.Equal(epoch.ToString(), root.GetProperty("streamEpoch").GetString());
        Assert.Equal(1000, root.GetProperty("samplePeriodMs").GetInt32());
    }

    [Fact]
    public void Snapshot_omits_age_for_unavailable_sensor_and_keeps_null_value()
    {
        var catalog = new Catalog(
            3,
            [new DeviceDescriptor("gpu-01", DeviceKind.Gpu, "GPU")],
            [new SensorDescriptor("gpu-01-hotspot", "gpu-01", "gpu.temperature.hotspot", "Hotspot", SensorUnit.Celsius, "lhm.gpu.hotspot", 1000)]);

        var snapshot = new Snapshot(
            Guid.NewGuid(),
            3,
            41,
            DateTimeOffset.UtcNow,
            41000,
            [
                new SensorReading("gpu-01-hotspot", SensorStatus.Unavailable, null, SensorReason.PermissionDenied, null),
            ]);

        var json = JsonSerializer.Serialize(
            FrameMapper.ToSnapshotMessage(new Core.Collection.TelemetryFrame(catalog, snapshot)),
            ProtocolJson.Options);

        using var document = JsonDocument.Parse(json);
        var sample = document.RootElement.GetProperty("samples")[0];

        Assert.Equal("unavailable", sample.GetProperty("status").GetString());
        Assert.Equal("permission_denied", sample.GetProperty("reason").GetString());
        Assert.Equal(JsonValueKind.Null, sample.GetProperty("value").ValueKind);
        Assert.False(sample.TryGetProperty("ageMs", out _));
        Assert.EndsWith("Z", document.RootElement.GetProperty("capturedAtUtc").GetString());
    }

    [Fact]
    public void Catalog_keeps_declared_units_and_source()
    {
        var catalog = new Catalog(
            1,
            [new DeviceDescriptor("cpu-01", DeviceKind.Cpu, "Процессор")],
            [new SensorDescriptor("cpu-01-time", "cpu-01", "cpu.time_busy.percent", "Занятость CPU по времени", SensorUnit.Percent, "win32.GetSystemTimes", 1000)]);

        var message = FrameMapper.ToCatalogMessage(new Core.Collection.TelemetryFrame(catalog, null!));

        Assert.Equal("percent", message.Sensors[0].Unit);
        Assert.Equal("win32.GetSystemTimes", message.Sensors[0].Source);
        Assert.Equal(1000, message.Sensors[0].SamplePeriodMs);
    }
}
