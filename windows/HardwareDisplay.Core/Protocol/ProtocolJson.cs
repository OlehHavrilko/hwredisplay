using System.Text.Json;
using System.Text.Json.Serialization;

namespace HardwareDisplay.Core.Protocol;

public sealed class ProtocolJson
{
    public const int Version = 1;
    public const string SubProtocol = "hardwaredisplay.v1";
    public const string V1 = "v1";

    public static JsonSerializerOptions Options { get; } = new(JsonSerializerDefaults.General)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new UtcTimestampConverter() },
    };
}

public sealed record DeviceDto(
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("label")] string Label);

public sealed record SensorDto(
    [property: JsonPropertyName("sensorId")] string SensorId,
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("metricKey")] string MetricKey,
    [property: JsonPropertyName("label")] string Label,
    [property: JsonPropertyName("unit")] string Unit,
    [property: JsonPropertyName("source")] string Source,
    [property: JsonPropertyName("samplePeriodMs")] int SamplePeriodMs);

public sealed record SampleDto(
    [property: JsonPropertyName("sensorId")] string SensorId,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("value"), JsonIgnore(Condition = JsonIgnoreCondition.Never)] double? Value,
    [property: JsonPropertyName("reason")] string? Reason,
    [property: JsonPropertyName("ageMs")] long? AgeMs);

public sealed record HelloMessage(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("hostId")] string HostId,
    [property: JsonPropertyName("streamEpoch")] string StreamEpoch,
    [property: JsonPropertyName("samplePeriodMs")] int SamplePeriodMs)
{
    public static HelloMessage Create(string hostId, Guid streamEpoch, int samplePeriodMs) =>
        new(ProtocolJson.Version, "hello", hostId, streamEpoch.ToString(), samplePeriodMs);
}

public sealed record CatalogMessage(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("revision")] long Revision,
    [property: JsonPropertyName("devices")] IReadOnlyList<DeviceDto> Devices,
    [property: JsonPropertyName("sensors")] IReadOnlyList<SensorDto> Sensors);

public sealed record SnapshotMessage(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("streamEpoch")] string StreamEpoch,
    [property: JsonPropertyName("revision")] long Revision,
    [property: JsonPropertyName("seq")] long Seq,
    [property: JsonPropertyName("capturedAtUtc")] DateTimeOffset CapturedAtUtc,
    [property: JsonPropertyName("monotonicMs")] long MonotonicMs,
    [property: JsonPropertyName("samples")] IReadOnlyList<SampleDto> Samples);

public sealed record PairRequest(
    [property: JsonPropertyName("v")] int? Version,
    [property: JsonPropertyName("invite")] string? Invite,
    [property: JsonPropertyName("clientName")] string? ClientName);

public sealed record PairResponse(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("hostId")] string HostId,
    [property: JsonPropertyName("clientId")] string ClientId);

public sealed record ErrorResponse(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("error")] string Error,
    [property: JsonPropertyName("reason")] string? Reason);

/// <summary>
/// Кадры протокола в формате `2026-11-12T11:04:42.123Z`: секунды с миллисекундами и явным UTC.
/// </summary>
public sealed class UtcTimestampConverter : JsonConverter<DateTimeOffset>
{
    private const string Format = "yyyy-MM-dd'T'HH:mm:ss.fff'Z'";

    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var text = reader.GetString() ?? throw new JsonException("Ожидалась метка времени.");
        return DateTimeOffset.Parse(text, System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.AdjustToUniversal | System.Globalization.DateTimeStyles.AssumeUniversal);
    }

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options) =>
        writer.WriteStringValue(value.ToUniversalTime().ToString(Format, System.Globalization.CultureInfo.InvariantCulture));
}
