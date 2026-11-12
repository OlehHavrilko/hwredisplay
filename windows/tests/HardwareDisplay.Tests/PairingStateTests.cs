using HardwareDisplay.Host.Pairing;
using HardwareDisplay.Tests.Support;

namespace HardwareDisplay.Tests;

public sealed class PairingStateTests
{
    private static PairingState Create(TempDirectory dir) => new(dir.File("clients.json"));

    [Fact]
    public void Pairs_once_per_invite_and_authorizes_token()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);
        var invite = pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        var result = pairing.TryPair("token-1", invite.Code, "Телефон");

        Assert.Equal(PairOutcome.Paired, result.Outcome);
        Assert.Equal("Телефон", result.Client!.Name);
        Assert.True(pairing.TryAuthorize("token-1", out var client));
        Assert.Equal(result.Client.ClientId, client.ClientId);
        Assert.Single(pairing.Clients);
    }

    [Fact]
    public void Rejects_reused_invite()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);
        var invite = pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        pairing.TryPair("token-1", invite.Code, "Первый");
        var second = pairing.TryPair("token-2", invite.Code, "Второй");

        Assert.Equal(PairOutcome.InvalidInvite, second.Outcome);
        Assert.Single(pairing.Clients);
    }

    [Fact]
    public void Rejects_expired_invite()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);
        var invite = pairing.EnsureInvite(TimeSpan.FromMilliseconds(20));
        Thread.Sleep(60);

        var result = pairing.TryPair("token-1", invite.Code, "Телефон");

        Assert.Equal(PairOutcome.InvalidInvite, result.Outcome);
    }

    [Fact]
    public void Reuses_active_invite_instead_of_creating_second()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);

        var first = pairing.EnsureInvite(TimeSpan.FromMinutes(5));
        var second = pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        Assert.Equal(first.Code, second.Code);
    }

    [Fact]
    public void Rejects_repeated_bearer_token()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);

        pairing.TryPair("same-token", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, "Первый");
        var second = pairing.TryPair("same-token", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, "Второй");

        Assert.Equal(PairOutcome.TokenConflict, second.Outcome);
    }

    [Fact]
    public void Enforces_client_limit()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);

        for (var i = 0; i < PairingState.MaxClients; i++)
        {
            var outcome = pairing.TryPair($"token-{i}", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, $"Телефон {i}");
            Assert.Equal(PairOutcome.Paired, outcome.Outcome);
        }

        var overflow = pairing.TryPair("token-extra", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, "Лишний");

        Assert.Equal(PairOutcome.ClientLimit, overflow.Outcome);
    }

    [Fact]
    public void Revoke_stops_authorization_and_raises_event()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);
        var paired = pairing.TryPair("token-1", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, "Телефон").Client!;

        string? revoked = null;
        pairing.ClientRevoked += id => revoked = id;

        Assert.True(pairing.Revoke(paired.ClientId));
        Assert.Equal(paired.ClientId, revoked);
        Assert.False(pairing.TryAuthorize("token-1", out _));
        Assert.Empty(pairing.Clients);
    }

    [Fact]
    public void Restores_clients_from_disk_without_plaintext_tokens()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);
        pairing.TryPair("token-secret", pairing.EnsureInvite(TimeSpan.FromMinutes(5)).Code, "Телефон");

        var stored = File.ReadAllText(dir.File("clients.json"));
        Assert.DoesNotContain("token-secret", stored, StringComparison.Ordinal);

        var restored = Create(dir);
        Assert.True(restored.TryAuthorize("token-secret", out var client));
        Assert.Equal("Телефон", client.Name);
    }

    [Fact]
    public void Invite_code_contains_128_random_bits()
    {
        using var dir = new TempDirectory();
        var pairing = Create(dir);

        var invite = pairing.EnsureInvite(TimeSpan.FromMinutes(5));

        Assert.Matches("^[0-9a-f]{32}$", invite.Code);
        Assert.True(invite.Remaining > TimeSpan.Zero);
    }
}
