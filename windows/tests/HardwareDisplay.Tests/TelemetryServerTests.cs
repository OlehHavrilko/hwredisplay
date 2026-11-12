using System.Net;
using System.Text.Json;
using HardwareDisplay.Tests.Support;

namespace HardwareDisplay.Tests;

public sealed class TelemetryServerTests
{
    [Fact]
    public async Task Session_without_token_is_unauthorized()
    {
        await using var harness = await ServerHarness.StartAsync();

        using var response = await harness.SessionAsync(token: null);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
    }

    [Fact]
    public async Task Session_with_unknown_token_is_unauthorized()
    {
        await using var harness = await ServerHarness.StartAsync();

        using var response = await harness.SessionAsync("foreign-token");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Request_with_foreign_host_is_rejected()
    {
        await using var harness = await ServerHarness.StartAsync();

        using var response = await harness.SessionAsync("some-token", host: "192.168.1.20:18443");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Request_with_origin_is_rejected()
    {
        await using var harness = await ServerHarness.StartAsync();

        using var response = await harness.SessionAsync("some-token", origin: "https://evil.test");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Pair_and_session_accept_issued_token()
    {
        await using var harness = await ServerHarness.StartAsync();
        var invite = harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        var pairJson = await harness.PairAsync("phone-token", invite.Code);
        using var pair = JsonDocument.Parse(pairJson);
        var clientId = pair.RootElement.GetProperty("clientId").GetString();

        Assert.Equal(harness.Identity.HostIdText, pair.RootElement.GetProperty("hostId").GetString());
        Assert.NotNull(clientId);

        using var response = await harness.SessionAsync("phone-token");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Pair_rejects_foreign_invite()
    {
        await using var harness = await ServerHarness.StartAsync();
        harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = new StringContent(
                """{"v":1,"invite":"00000000000000000000000000000000","clientName":"Телефон"}""",
                System.Text.Encoding.UTF8,
                "application/json"),
        };
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", "phone-token");

        using var response = await harness.Http.SendAsync(request);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Stream_delivers_hello_catalog_and_snapshot_in_order()
    {
        await using var harness = await ServerHarness.StartAsync();
        var invite = harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));
        await harness.PairAsync("phone-token", invite.Code);

        var (status, _, socket, _) = await harness.OpenTelemetryAsync("phone-token");
        Assert.Equal(101, status);
        using var ws = socket!;
        await using var reader = new WsReader(ws);

        using var hello = await reader.ReadJsonAsync();
        Assert.Equal("hello", hello.RootElement.GetProperty("type").GetString());
        Assert.Equal(harness.Identity.HostIdText, hello.RootElement.GetProperty("hostId").GetString());
        Assert.Equal(1, hello.RootElement.GetProperty("v").GetInt32());
        var epoch = hello.RootElement.GetProperty("streamEpoch").GetString();

        using var catalog = await reader.ReadJsonAsync();
        Assert.Equal("catalog", catalog.RootElement.GetProperty("type").GetString());
        Assert.Equal(1, catalog.RootElement.GetProperty("revision").GetInt64());
        Assert.Equal("sensor-1", catalog.RootElement.GetProperty("sensors")[0].GetProperty("sensorId").GetString());

        JsonDocument? snapshot = null;
        for (var attempt = 0; attempt < 20 && snapshot is null; attempt++)
        {
            var candidate = await reader.ReadJsonAsync();
            var isOkSample = candidate.RootElement.GetProperty("type").GetString() == "snapshot" &&
                             candidate.RootElement.GetProperty("samples")[0].GetProperty("status").GetString() == "ok";

            if (isOkSample)
            {
                snapshot = candidate;
            }
            else
            {
                candidate.Dispose();
            }
        }

        Assert.NotNull(snapshot);
        Assert.Equal(epoch, snapshot.RootElement.GetProperty("streamEpoch").GetString());
        Assert.Equal(1, snapshot.RootElement.GetProperty("revision").GetInt64());

        var sample = snapshot.RootElement.GetProperty("samples")[0];
        Assert.Equal("sensor-1", sample.GetProperty("sensorId").GetString());
        Assert.Equal(42, sample.GetProperty("value").GetDouble());
        Assert.True(sample.TryGetProperty("ageMs", out _));

        Assert.EndsWith("Z", snapshot.RootElement.GetProperty("capturedAtUtc").GetString());
        snapshot.Dispose();
    }

    [Fact]
    public async Task Stream_increases_seq_for_each_snapshot()
    {
        await using var harness = await ServerHarness.StartAsync();
        var invite = harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));
        await harness.PairAsync("phone-token", invite.Code);

        var (status, _, socket, _) = await harness.OpenTelemetryAsync("phone-token");
        Assert.Equal(101, status);
        using var ws = socket!;
        await using var reader = new WsReader(ws);

        using var hello = await reader.ReadJsonAsync();
        using var catalog = await reader.ReadJsonAsync();
        using var first = await reader.ReadJsonAsync();
        using var second = await reader.ReadJsonAsync();

        var firstSeq = first.RootElement.GetProperty("seq").GetInt64();
        var secondSeq = second.RootElement.GetProperty("seq").GetInt64();

        Assert.True(secondSeq > firstSeq);
        Assert.Equal(
            first.RootElement.GetProperty("streamEpoch").GetString(),
            second.RootElement.GetProperty("streamEpoch").GetString());
    }

    [Fact]
    public async Task Stream_requires_token_and_subprotocol()
    {
        await using var harness = await ServerHarness.StartAsync();
        var invite = harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));
        await harness.PairAsync("phone-token", invite.Code);

        var (noToken, _, _, _) = await harness.OpenTelemetryAsync("phone-token", includeAuthorization: false);
        Assert.Equal(401, noToken);

        var (noProtocol, _, _, _) = await harness.OpenTelemetryAsync("phone-token", subProtocol: null);
        Assert.Equal(400, noProtocol);

        var (foreignHost, _, _, _) = await harness.OpenTelemetryAsync("phone-token", host: "127.0.0.1");
        Assert.Equal(400, foreignHost);
    }

    [Fact]
    public async Task Revoke_closes_open_stream_with_policy_violation()
    {
        await using var harness = await ServerHarness.StartAsync();
        var invite = harness.Pairing.EnsureInvite(TimeSpan.FromMinutes(5));
        await harness.PairAsync("phone-token", invite.Code);
        var clientId = harness.Pairing.Clients.Single().ClientId;

        var (status, _, socket, _) = await harness.OpenTelemetryAsync("phone-token");
        Assert.Equal(101, status);
        using var ws = socket!;
        await using var reader = new WsReader(ws);

        using var hello = await reader.ReadJsonAsync();
        using var catalog = await reader.ReadJsonAsync();
        using var snapshot = await reader.ReadJsonAsync();

        Assert.True(harness.Pairing.Revoke(clientId));

        var closeStatus = await reader.WaitCloseAsync();

        Assert.Equal((System.Net.WebSockets.WebSocketCloseStatus)1008, closeStatus);
        Assert.Equal(0, harness.Server.ActiveSessions);
    }
}
