using System.IO;
using System.Net;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Providers;
using HardwareDisplay.Host.Identity;
using HardwareDisplay.Host.Net;
using HardwareDisplay.Host.Pairing;
using HardwareDisplay.Host.Server;
using HardwareDisplay.Windows.Providers;

namespace HardwareDisplay.Host.Runtime;

public sealed class AppRuntime : IAsyncDisposable
{
    private static readonly int[] CandidatePorts = [18443, 18444, 18445];

    private AppRuntime(string dataDirectory, HostIdentity identity, PairingState pairing, TelemetryCollector collector, TelemetryServer server)
    {
        DataDirectory = dataDirectory;
        Identity = identity;
        Pairing = pairing;
        Collector = collector;
        Server = server;
    }

    public string DataDirectory { get; }

    public HostIdentity Identity { get; }

    public PairingState Pairing { get; }

    public TelemetryCollector Collector { get; }

    public TelemetryServer Server { get; }

    public int Port { get; private set; }

    public string Diagnostic { get; private set; } = string.Empty;

    public static AppRuntime Create(string? dataDirectory = null)
    {
        var directory = dataDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "HardwareDisplay");

        Directory.CreateDirectory(directory);

        var identity = HostIdentity.LoadOrCreate(Path.Combine(directory, "identity"));
        var pairing = new PairingState(Path.Combine(directory, "clients.json"));

        var collector = new TelemetryCollector([new CpuTimeProvider(), new MemoryStatusProvider()]);
        var server = new TelemetryServer(identity, pairing, collector);

        return new AppRuntime(directory, identity, pairing, collector, server);
    }

    public async Task<bool> StartAsync()
    {
        if (Server.IsRunning)
        {
            return true;
        }

        if (Identity.IsExpired)
        {
            Diagnostic = "Срок действия сертификата истёк. Нужна новая идентичность и повторное сопряжение.";
            return false;
        }

        Collector.Start();

        var candidates = LanAddresses.Candidates();
        if (candidates.Count == 0)
        {
            Diagnostic = "Сетевой адрес для телефона не найден. Подключите Wi-Fi или Ethernet и повторите.";
            return false;
        }

        var failures = new List<string>();
        foreach (var address in candidates)
        {
            foreach (var port in CandidatePorts)
            {
                try
                {
                    await Server.StartAsync(address, port).ConfigureAwait(false);
                    Port = Server.BoundPort;
                    Diagnostic = string.Empty;
                    Pairing.EnsureInvite(PairingState.ManualInviteLifetime);
                    return true;
                }
                catch (ServerStartException ex)
                {
                    failures.Add(ex.Message);
                }
            }
        }

        Diagnostic = failures.Count > 0
            ? "Не удалось открыть порт: " + string.Join(" ", failures.Take(3))
            : "Не удалось запустить сервер.";

        return false;
    }

    /// <summary>
    /// Останавливает только сетевой сервер: сбор датчиков продолжается, чтобы окно
    /// показывало живые значения и после повторного запуска LAN.
    /// </summary>
    public Task StopAsync() => Server.StopAsync();

    public PairingInvite RefreshInvite() => Pairing.EnsureInvite(PairingState.ManualInviteLifetime);

    public IPAddress? Address => Server.BoundAddress;

    public string HostName => Identity.CanonicalName;

    public ValueTask DisposeAsync() => Collector.DisposeAsync();
}
