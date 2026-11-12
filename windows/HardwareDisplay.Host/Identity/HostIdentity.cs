using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace HardwareDisplay.Host.Identity;

public sealed class HostIdentity
{
    private const string HostIdFile = "hostid.txt";
    private const string CertificateFile = "identity.pfx.dpapi";

    private HostIdentity(Guid hostId, X509Certificate2 certificate)
    {
        HostId = hostId;
        Certificate = certificate;
        CanonicalName = CanonicalNameFor(hostId);
        FingerprintSha256 = Convert.ToHexStringLower(SHA256.HashData(certificate.RawData));
    }

    public Guid HostId { get; }

    public string HostIdText => HostId.ToString("D");

    public string CanonicalName { get; }

    public X509Certificate2 Certificate { get; }

    public string FingerprintSha256 { get; }

    public DateTimeOffset NotAfter => new(Certificate.NotAfter.ToUniversalTime(), TimeSpan.Zero);

    public bool IsExpired => Certificate.NotAfter.ToUniversalTime() <= DateTime.UtcNow;

    public static string CanonicalNameFor(Guid hostId) => $"pc-{hostId:D}.hardwaredisplay.test";

    /// <summary>
    /// Ключ и сертификат хранятся в DPAPI CurrentUser. Потеря файла означает новую
    /// идентичность ПК: сопряжённые телефоны потребуют новое приглашение.
    /// </summary>
    public static HostIdentity LoadOrCreate(string directory)
    {
        Directory.CreateDirectory(directory);

        var hostIdPath = Path.Combine(directory, HostIdFile);
        var certPath = Path.Combine(directory, CertificateFile);

        var hostId = LoadOrCreateHostId(hostIdPath);

        if (File.Exists(certPath))
        {
            var pfx = Dpapi.Unprotect(File.ReadAllBytes(certPath));
            var existing = X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.Exportable);
            if (existing.HasPrivateKey && existing.Subject.Contains(CanonicalNameFor(hostId), StringComparison.Ordinal))
            {
                return new HostIdentity(hostId, existing);
            }

            existing.Dispose();
        }

        var created = CreateCertificate(hostId);
        File.WriteAllBytes(certPath, Dpapi.Protect(created.Export(X509ContentType.Pfx)));

        return new HostIdentity(hostId, created);
    }

    private static Guid LoadOrCreateHostId(string path)
    {
        if (File.Exists(path))
        {
            var text = File.ReadAllText(path).Trim();
            if (Guid.TryParseExact(text, "D", out var stored))
            {
                return stored;
            }
        }

        var hostId = Guid.NewGuid();
        File.WriteAllText(path, hostId.ToString("D"));
        return hostId;
    }

    private static X509Certificate2 CreateCertificate(Guid hostId)
    {
        var name = CanonicalNameFor(hostId);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        var request = new CertificateRequest($"CN={name}", key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid("1.3.6.1.5.5.7.3.1")], false));

        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(name);
        request.CertificateExtensions.Add(san.Build());

        var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddDays(365));

        var pfx = certificate.Export(X509ContentType.Pfx);
        certificate.Dispose();

        return X509CertificateLoader.LoadPkcs12(pfx, null, X509KeyStorageFlags.Exportable);
    }
}
