using System.Text.Json;
using System.Text.Json.Serialization;

namespace HardwareDisplay.Host.Pairing;

public sealed record InvitePayload(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("hostId")] string HostId,
    [property: JsonPropertyName("ip")] string Ip,
    [property: JsonPropertyName("port")] int Port,
    [property: JsonPropertyName("certSha256")] string CertSha256,
    [property: JsonPropertyName("invite")] string Invite)
{
    public static InvitePayload Create(string hostId, string ip, int port, string certSha256, string invite) =>
        new(1, hostId, ip, port, certSha256, invite);

    public string ToJson() => JsonSerializer.Serialize(this);
}
