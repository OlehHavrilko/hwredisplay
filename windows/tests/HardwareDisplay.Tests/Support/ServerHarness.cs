using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Protocol;
using HardwareDisplay.Host.Identity;
using HardwareDisplay.Host.Pairing;
using HardwareDisplay.Host.Server;

namespace HardwareDisplay.Tests.Support;

/// <summary>
/// Клиент повторяет схему Android: TLS до логического имени ПК, TCP на известный адрес,
/// проверка отпечатка листа и явный Host. Тот же путь проверяет отказ до отправки секретов.
/// </summary>
internal sealed class ServerHarness : IAsyncDisposable
{
    private readonly TempDirectory _dir;

    private ServerHarness(
        TempDirectory dir,
        HostIdentity identity,
        PairingState pairing,
        TelemetryCollector collector,
        TelemetryServer server,
        int port,
        HttpClient http)
    {
        _dir = dir;
        Identity = identity;
        Pairing = pairing;
        Collector = collector;
        Server = server;
        Port = port;
        Http = http;
    }

    public HostIdentity Identity { get; }

    public PairingState Pairing { get; }

    public TelemetryCollector Collector { get; }

    public TelemetryServer Server { get; }

    public int Port { get; }

    public HttpClient Http { get; }

    public static async Task<ServerHarness> StartAsync()
    {
        var dir = new TempDirectory();
        var identity = HostIdentity.LoadOrCreate(dir.Path);
        var pairing = new PairingState(dir.File("clients.json"));
        var provider = new FakeProvider("test", FakeProvider.Sensor("sensor-1"))
            .Set("sensor-1", Core.Providers.ProviderValue.Ok(42));

        var collector = new TelemetryCollector([provider], Telemetry.Fast);
        collector.Start();

        var port = 0;
        var server = new TelemetryServer(identity, pairing, collector);
        await server.StartAsync(IPAddress.Loopback, port);
        port = server.BoundPort;

        var handler = new SocketsHttpHandler
        {
            ConnectCallback = async (_, token) =>
            {
                var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                await socket.ConnectAsync(new IPEndPoint(IPAddress.Loopback, port), token);
                return new NetworkStream(socket, ownsSocket: true);
            },
            SslOptions = { RemoteCertificateValidationCallback = (_, cert, _, _) => PinMatches(identity, cert) },
        };

        var http = new HttpClient(handler) { BaseAddress = new Uri($"https://{identity.CanonicalName}:{port}") };

        return new ServerHarness(dir, identity, pairing, collector, server, port, http);
    }

    public Task<HttpResponseMessage> SessionAsync(string? token, string? host = null, string? origin = null)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, "/v1/session");
        if (token is not null)
        {
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        }

        if (host is not null)
        {
            request.Headers.Host = host;
        }

        if (origin is not null)
        {
            request.Headers.Add("Origin", origin);
        }

        return Http.SendAsync(request);
    }

    public async Task<(int Status, string Headers, WebSocket? Socket, SslStream? Stream)> OpenTelemetryAsync(
        string token,
        string? subProtocol = ProtocolJson.SubProtocol,
        bool includeAuthorization = true,
        string? host = null)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, Port);
        var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false, (_, cert, _, _) => PinMatches(Identity, cert));
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = Identity.CanonicalName,
            ApplicationProtocols = [SslApplicationProtocol.Http11],
        });

        var key = Convert.ToBase64String(RandomNumberGenerator.GetBytes(16));
        var request = new StringBuilder()
            .Append("GET /v1/telemetry HTTP/1.1\r\n")
            .Append($"Host: {host ?? $"{Identity.CanonicalName}:{Port}"}\r\n")
            .Append("Upgrade: websocket\r\n")
            .Append("Connection: Upgrade\r\n")
            .Append($"Sec-WebSocket-Key: {key}\r\n")
            .Append("Sec-WebSocket-Version: 13\r\n");

        if (subProtocol is not null)
        {
            request.Append($"Sec-WebSocket-Protocol: {subProtocol}\r\n");
        }

        if (includeAuthorization)
        {
            request.Append($"Authorization: Bearer {token}\r\n");
        }

        request.Append("\r\n");
        await ssl.WriteAsync(Encoding.ASCII.GetBytes(request.ToString()));

        var headers = await ReadHeadersAsync(ssl);
        var status = int.Parse(headers.Split(' ')[1], System.Globalization.CultureInfo.InvariantCulture);

        if (status != 101)
        {
            ssl.Dispose();
            tcp.Dispose();
            return (status, headers, null, null);
        }

        var socket = WebSocket.CreateFromStream(ssl, new WebSocketCreationOptions
        {
            IsServer = false,
            SubProtocol = ProtocolJson.SubProtocol,
        });

        return (status, headers, socket, ssl);
    }

    public async Task<string> PairAsync(string token, string invite, string name = "Телефон")
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = new StringContent(
                JsonSerializer.Serialize(new { v = 1, invite, clientName = name }),
                Encoding.UTF8,
                "application/json"),
        };
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);

        using var response = await Http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadAsStringAsync();
    }

    public static async Task<JsonDocument> ReceiveJsonAsync(WebSocket socket, int timeoutMs = 5000)
    {
        using var cts = new CancellationTokenSource(timeoutMs);
        var buffer = new byte[128 * 1024];
        using var payload = new MemoryStream();

        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, cts.Token);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                throw new InvalidOperationException($"Сокет закрыт: {result.CloseStatus} {result.CloseStatusDescription}");
            }

            payload.Write(buffer, 0, result.Count);
            if (result.EndOfMessage)
            {
                return JsonDocument.Parse(payload.ToArray());
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        Http.Dispose();
        await Server.StopAsync();
        await Collector.DisposeAsync();
        _dir.Dispose();
    }

    private static bool PinMatches(HostIdentity identity, X509Certificate? certificate)
    {
        if (certificate is null)
        {
            return false;
        }

        return Convert.ToHexStringLower(SHA256.HashData(certificate.GetRawCertData())) == identity.FingerprintSha256;
    }

    private static async Task<string> ReadHeadersAsync(Stream stream)
    {
        var bytes = new List<byte>(1024);
        var one = new byte[1];

        while (bytes.Count < 16 * 1024)
        {
            if (await stream.ReadAsync(one) == 0)
            {
                break;
            }

            bytes.Add(one[0]);
            var count = bytes.Count;
            if (count >= 4 && bytes[count - 4] == '\r' && bytes[count - 3] == '\n' && bytes[count - 2] == '\r' && bytes[count - 1] == '\n')
            {
                break;
            }
        }

        return Encoding.ASCII.GetString(bytes.ToArray());
    }

    private static int FreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }
}
