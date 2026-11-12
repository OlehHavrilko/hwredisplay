using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace HardwareDisplay.Host.Pairing;

public sealed record PairedClient(
    [property: JsonPropertyName("clientId")] string ClientId,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("tokenSha256")] string TokenSha256,
    [property: JsonPropertyName("pairedAtUtc")] DateTimeOffset PairedAtUtc);

public sealed record PairingInvite(string Code, DateTimeOffset ExpiresAtUtc)
{
    public bool IsExpired => DateTimeOffset.UtcNow >= ExpiresAtUtc;

    public TimeSpan Remaining => ExpiresAtUtc - DateTimeOffset.UtcNow;
}

public enum PairOutcome
{
    Paired,
    InvalidInvite,
    TokenConflict,
    ClientLimit,
}

public sealed record PairResult(PairOutcome Outcome, PairedClient? Client);

/// <summary>
/// Одно активное приглашение и отдельная учётная запись на каждый телефон.
/// Сервер хранит только SHA-256 bearer-токена.
/// </summary>
public sealed class PairingState
{
    public static readonly TimeSpan QrInviteLifetime = TimeSpan.FromSeconds(120);
    public static readonly TimeSpan ManualInviteLifetime = TimeSpan.FromSeconds(300);
    public const int MaxClients = 4;

    private readonly object _gate = new();
    private readonly string _storagePath;
    private readonly List<PairedClient> _clients;
    private PairingInvite? _invite;

    public PairingState(string storagePath)
    {
        _storagePath = storagePath;
        _clients = LoadClients(storagePath);
    }

    public event Action<string>? ClientRevoked;

    public event Action? ClientsChanged;

    public IReadOnlyList<PairedClient> Clients
    {
        get { lock (_gate) { return _clients.ToArray(); } }
    }

    public PairingInvite? CurrentInvite
    {
        get { lock (_gate) { return _invite; } }
    }

    /// <summary>
    /// Если действующее приглашение не истекло, оно возвращается повторно:
    /// одновременно активно только одно приглашение.
    /// </summary>
    public PairingInvite EnsureInvite(TimeSpan lifetime)
    {
        lock (_gate)
        {
            if (_invite is { } current && !current.IsExpired)
            {
                return current;
            }

            _invite = new PairingInvite(
                Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)),
                DateTimeOffset.UtcNow.Add(lifetime));

            return _invite;
        }
    }

    public PairResult TryPair(string bearerToken, string inviteCode, string clientName)
    {
        var tokenHash = HashToken(bearerToken);
        var clientId = Guid.NewGuid().ToString("D");

        lock (_gate)
        {
            if (_invite is null || _invite.IsExpired ||
                !CryptographicOperations.FixedTimeEquals(
                    Encoding.ASCII.GetBytes(_invite.Code),
                    Encoding.ASCII.GetBytes(inviteCode.Trim().ToLowerInvariant())))
            {
                return new PairResult(PairOutcome.InvalidInvite, null);
            }

            if (_clients.Any(c => c.TokenSha256 == tokenHash))
            {
                return new PairResult(PairOutcome.TokenConflict, null);
            }

            if (_clients.Count >= MaxClients)
            {
                return new PairResult(PairOutcome.ClientLimit, null);
            }

            var client = new PairedClient(
                clientId,
                string.IsNullOrWhiteSpace(clientName) ? "Телефон" : clientName.Trim(),
                tokenHash,
                DateTimeOffset.UtcNow);

            _clients.Add(client);
            _invite = null;
            SaveClients();
            return new PairResult(PairOutcome.Paired, client);
        }
    }

    public bool TryAuthorize(string? bearerToken, out PairedClient client)
    {
        client = null!;
        if (string.IsNullOrWhiteSpace(bearerToken))
        {
            return false;
        }

        var tokenHash = HashToken(bearerToken);

        lock (_gate)
        {
            foreach (var candidate in _clients)
            {
                if (candidate.TokenSha256 == tokenHash)
                {
                    client = candidate;
                    return true;
                }
            }
        }

        return false;
    }

    public bool Revoke(string clientId)
    {
        lock (_gate)
        {
            var index = _clients.FindIndex(c => c.ClientId == clientId);
            if (index < 0)
            {
                return false;
            }

            _clients.RemoveAt(index);
            SaveClients();
        }

        ClientRevoked?.Invoke(clientId);
        ClientsChanged?.Invoke();
        return true;
    }

    public void NotifyClientsChanged() => ClientsChanged?.Invoke();

    private static string HashToken(string token) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    private List<PairedClient> LoadClients(string path)
    {
        if (!File.Exists(path))
        {
            return [];
        }

        try
        {
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<List<PairedClient>>(json) ?? [];
        }
        catch (Exception)
        {
            return [];
        }
    }

    private void SaveClients()
    {
        var directory = Path.GetDirectoryName(_storagePath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        File.WriteAllText(_storagePath, JsonSerializer.Serialize(_clients, new JsonSerializerOptions { WriteIndented = true }));
    }
}
