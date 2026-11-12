using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text.Json;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Protocol;
using HardwareDisplay.Host.Identity;
using HardwareDisplay.Host.Pairing;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace HardwareDisplay.Host.Server;

public sealed class TelemetryServer
{
    private const int MaxBodyBytes = 4096;

    private readonly HostIdentity _identity;
    private readonly PairingState _pairing;
    private readonly TelemetryCollector _collector;
    private readonly Dictionary<string, SessionHandle> _sessions = new(StringComparer.Ordinal);
    private readonly object _sessionsGate = new();

    private WebApplication? _app;

    public TelemetryServer(HostIdentity identity, PairingState pairing, TelemetryCollector collector)
    {
        _identity = identity;
        _pairing = pairing;
        _collector = collector;
        _pairing.ClientRevoked += RevokeSessions;
    }

    public bool IsRunning => _app is not null;

    public IPAddress? BoundAddress { get; private set; }

    public int BoundPort { get; private set; }

    public int ActiveSessions
    {
        get { lock (_sessionsGate) { return _sessions.Count; } }
    }

    public async Task StartAsync(IPAddress address, int port)
    {
        if (_app is not null)
        {
            throw new InvalidOperationException("Сервер уже запущен.");
        }

        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions
        {
            ContentRootPath = AppContext.BaseDirectory,
            EnvironmentName = "Production",
        });

        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Limits.MaxRequestBodySize = MaxBodyBytes;
            options.Listen(address, port, listen =>
            {
                listen.Protocols = HttpProtocols.Http1;
                listen.UseHttps(_identity.Certificate);
            });
        });

        var app = builder.Build();
        app.UseWebSockets();
        app.Use(GuardAsync);
        app.MapPost("/v1/pair", HandlePairAsync);
        app.MapGet("/v1/session", HandleSessionAsync);
        app.Map("/v1/telemetry", HandleTelemetryAsync);

        try
        {
            await app.StartAsync().ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            await app.DisposeAsync().ConfigureAwait(false);
            throw new ServerStartException($"Порт {address}:{port} недоступен: {ex.Message}", ex);
        }

        _app = app;
        BoundAddress = address;
        BoundPort = port != 0 ? port : ResolveBoundPort(app);
    }

    private static int ResolveBoundPort(WebApplication app)
    {
        var addresses = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()?.Addresses;
        var first = addresses?.FirstOrDefault();
        if (first is not null && Uri.TryCreate(first, UriKind.Absolute, out var uri))
        {
            return uri.Port;
        }

        throw new ServerStartException("Не удалось определить занятый порт.", new InvalidOperationException(first));
    }

    public async Task StopAsync()
    {
        var app = _app;
        if (app is null)
        {
            return;
        }

        _app = null;

        lock (_sessionsGate)
        {
            foreach (var handle in _sessions.Values)
            {
                handle.CloseStatus = WebSocketCloseStatus.EndpointUnavailable;
                handle.CloseReason = "server_stop";
                handle.Cancellation.Cancel();
            }
        }

        await Task.Delay(150).ConfigureAwait(false);
        await app.StopAsync().ConfigureAwait(false);
        await app.DisposeAsync().ConfigureAwait(false);
    }

    private async Task GuardAsync(HttpContext context, Func<Task> next)
    {
        if (context.Request.Headers.ContainsKey("Origin"))
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }

        var host = context.Request.Host;
        var canonical = string.Equals(host.Host, _identity.CanonicalName, StringComparison.OrdinalIgnoreCase);

        if (!canonical || (host.Port is { } requestedPort && requestedPort != BoundPort))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await WriteErrorAsync(context, "bad_host", _identity.CanonicalName).ConfigureAwait(false);
            return;
        }

        context.Response.Headers.CacheControl = "no-store";
        await next().ConfigureAwait(false);
    }

    private async Task HandlePairAsync(HttpContext context)
    {
        var token = ReadBearer(context.Request);
        if (token is null)
        {
            await UnauthorizedAsync(context).ConfigureAwait(false);
            return;
        }

        PairRequest? request;
        try
        {
            using var buffer = new MemoryStream();
            await context.Request.Body.CopyToAsync(buffer, context.RequestAborted).ConfigureAwait(false);
            request = JsonSerializer.Deserialize<PairRequest>(buffer.ToArray(), ProtocolJson.Options);
        }
        catch (Microsoft.AspNetCore.Http.BadHttpRequestException)
        {
            context.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
            return;
        }
        catch (JsonException)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await WriteErrorAsync(context, "bad_json", null).ConfigureAwait(false);
            return;
        }

        if (request?.Invite is null || request.Version != ProtocolJson.Version)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await WriteErrorAsync(context, "bad_request", null).ConfigureAwait(false);
            return;
        }

        var result = _pairing.TryPair(token, request.Invite, request.ClientName ?? "Телефон");
        switch (result.Outcome)
        {
            case PairOutcome.Paired:
                _pairing.NotifyClientsChanged();
                context.Response.StatusCode = StatusCodes.Status201Created;
                await context.Response.WriteAsJsonAsync(
                    new PairResponse(ProtocolJson.Version, _identity.HostIdText, result.Client!.ClientId),
                    ProtocolJson.Options,
                    context.RequestAborted).ConfigureAwait(false);
                return;

            case PairOutcome.InvalidInvite:
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                await WriteErrorAsync(context, "invalid_invite", null).ConfigureAwait(false);
                return;

            case PairOutcome.TokenConflict:
                context.Response.StatusCode = StatusCodes.Status409Conflict;
                await WriteErrorAsync(context, "token_conflict", null).ConfigureAwait(false);
                return;

            default:
                context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
                await WriteErrorAsync(context, "client_limit", null).ConfigureAwait(false);
                return;
        }
    }

    private async Task HandleSessionAsync(HttpContext context)
    {
        if (!_pairing.TryAuthorize(ReadBearer(context.Request), out var client))
        {
            await UnauthorizedAsync(context).ConfigureAwait(false);
            return;
        }

        await context.Response.WriteAsJsonAsync(
            new PairResponse(ProtocolJson.Version, _identity.HostIdText, client.ClientId),
            ProtocolJson.Options,
            context.RequestAborted).ConfigureAwait(false);
    }

    private async Task HandleTelemetryAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await WriteErrorAsync(context, "upgrade_required", ProtocolJson.SubProtocol).ConfigureAwait(false);
            return;
        }

        if (!_pairing.TryAuthorize(ReadBearer(context.Request), out var client))
        {
            await UnauthorizedAsync(context).ConfigureAwait(false);
            return;
        }

        if (!context.WebSockets.WebSocketRequestedProtocols.Contains(ProtocolJson.SubProtocol, StringComparer.Ordinal))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            await WriteErrorAsync(context, "unsupported_protocol", ProtocolJson.SubProtocol).ConfigureAwait(false);
            return;
        }

        SessionHandle? handle;
        lock (_sessionsGate)
        {
            if (_sessions.TryGetValue(client.ClientId, out var existing))
            {
                existing.CloseStatus = WebSocketCloseStatus.EndpointUnavailable;
                existing.CloseReason = "replaced";
                existing.Cancellation.Cancel();
                _sessions.Remove(client.ClientId);

                handle = new SessionHandle();
                _sessions[client.ClientId] = handle;
            }
            else if (_sessions.Count >= PairingState.MaxClients)
            {
                handle = null;
            }
            else
            {
                handle = new SessionHandle();
                _sessions[client.ClientId] = handle;
            }
        }

        if (handle is null)
        {
            context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
            await WriteErrorAsync(context, "session_limit", null).ConfigureAwait(false);
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync(new WebSocketAcceptContext
        {
            SubProtocol = ProtocolJson.SubProtocol,
            KeepAliveInterval = TimeSpan.FromSeconds(15),
            KeepAliveTimeout = TimeSpan.FromSeconds(30),
        }).ConfigureAwait(false);

        try
        {
            await TelemetrySession.RunAsync(socket, _collector, _identity.HostIdText, handle, TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        }
        finally
        {
            lock (_sessionsGate)
            {
                if (_sessions.TryGetValue(client.ClientId, out var current) && ReferenceEquals(current, handle))
                {
                    _sessions.Remove(client.ClientId);
                }
            }

            handle.Dispose();
        }
    }

    private void RevokeSessions(string clientId)
    {
        SessionHandle? handle;
        lock (_sessionsGate)
        {
            if (!_sessions.Remove(clientId, out handle))
            {
                return;
            }
        }

        handle.CloseStatus = WebSocketCloseStatus.PolicyViolation;
        handle.CloseReason = "revoked";
        handle.Cancellation.Cancel();
    }

    private static string? ReadBearer(HttpRequest request)
    {
        var header = request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        if (string.IsNullOrWhiteSpace(header) || !header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var token = header[prefix.Length..].Trim();
        return token.Length == 0 ? null : token;
    }

    private static Task UnauthorizedAsync(HttpContext context)
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        context.Response.Headers.WWWAuthenticate = "Bearer";
        return WriteErrorAsync(context, "unauthorized", null);
    }

    private static Task WriteErrorAsync(HttpContext context, string error, string? reason) =>
        context.Response.WriteAsJsonAsync(new ErrorResponse(ProtocolJson.Version, error, reason), ProtocolJson.Options, context.RequestAborted);
}

public sealed class ServerStartException(string message, Exception inner) : Exception(message, inner);
