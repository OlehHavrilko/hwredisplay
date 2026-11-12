using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using HardwareDisplay.Host.Identity;
using HardwareDisplay.Tests.Support;

namespace HardwareDisplay.Tests;

public sealed class HostIdentityTests
{
    [Fact]
    public void Creates_identity_with_canonical_name_and_fingerprint()
    {
        using var dir = new TempDirectory();

        var identity = HostIdentity.LoadOrCreate(dir.Path);

        Assert.Equal($"pc-{identity.HostId:D}.hardwaredisplay.test", identity.CanonicalName);
        Assert.Matches("^[0-9a-f]{64}$", identity.FingerprintSha256);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(identity.Certificate.RawData)), identity.FingerprintSha256);
        Assert.True(identity.Certificate.HasPrivateKey);
    }

    [Fact]
    public void Certificate_carries_server_auth_and_dns_san()
    {
        using var dir = new TempDirectory();

        var identity = HostIdentity.LoadOrCreate(dir.Path);
        var extensions = identity.Certificate.Extensions;

        var san = extensions.OfType<X509SubjectAlternativeNameExtension>().Single();
        Assert.Equal([identity.CanonicalName], san.EnumerateDnsNames());

        var eku = extensions.OfType<X509EnhancedKeyUsageExtension>().Single();
        Assert.Contains(eku.EnhancedKeyUsages.Cast<Oid>(), oid => oid.Value == "1.3.6.1.5.5.7.3.1");

        var keyUsage = extensions.OfType<X509KeyUsageExtension>().Single();
        Assert.Equal(X509KeyUsageFlags.DigitalSignature, keyUsage.KeyUsages);

        Assert.False(identity.IsExpired);
        Assert.Equal("1.2.840.10045.2.1", identity.Certificate.PublicKey.Oid.Value);
    }

    [Fact]
    public void Reloads_same_identity_from_disk()
    {
        using var dir = new TempDirectory();

        var first = HostIdentity.LoadOrCreate(dir.Path);
        var second = HostIdentity.LoadOrCreate(dir.Path);

        Assert.Equal(first.HostId, second.HostId);
        Assert.Equal(first.FingerprintSha256, second.FingerprintSha256);
    }

    [Fact]
    public void Stores_key_not_as_readable_pfx()
    {
        using var dir = new TempDirectory();

        HostIdentity.LoadOrCreate(dir.Path);
        var stored = File.ReadAllBytes(dir.File("identity.pfx.dpapi"));

        Assert.True(stored.Length > 0);
        Assert.NotEqual(0x30, stored[0]);
    }
}
