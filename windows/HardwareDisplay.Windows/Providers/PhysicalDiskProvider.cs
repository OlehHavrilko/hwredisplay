using System.Globalization;
using System.Text.RegularExpressions;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;

namespace HardwareDisplay.Windows.Providers;

/// <summary>
/// Активность физических дисков по счётчикам `PhysicalDisk`. Агрегат `_Total` не публикуется:
/// сумма по дискам имеет другой смысл, чем отдельное устройство.
/// </summary>
public sealed partial class PhysicalDiskProvider : ISensorProvider, IDisposable
{
    private const string ReadPath = @"\PhysicalDisk(*)\Disk Read Bytes/sec";
    private const string WritePath = @"\PhysicalDisk(*)\Disk Write Bytes/sec";
    private const string IdlePath = @"\PhysicalDisk(*)\% Idle Time";

    private readonly object _gate = new();
    private PdhQuery? _query;
    private PdhCounter? _read;
    private PdhCounter? _write;
    private PdhCounter? _idle;
    private IReadOnlyList<DiskInfo> _disks = [];

    public string Id => "pdh.PhysicalDisk";

    public TimeSpan PollInterval => TimeSpan.FromSeconds(1);

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices()
    {
        RefreshDisks();
        lock (_gate)
        {
            return _disks.Select(d => new DeviceDescriptor(d.DeviceId, DeviceKind.Storage, d.Label)).ToArray();
        }
    }

    public IReadOnlyList<SensorDescriptor> DiscoverSensors()
    {
        RefreshDisks();
        lock (_gate)
        {
            var sensors = new List<SensorDescriptor>(_disks.Count * 3);
            foreach (var disk in _disks)
            {
                sensors.Add(new SensorDescriptor(
                    $"{disk.DeviceId}-read",
                    disk.DeviceId,
                    "disk.read.bytes_per_second",
                    "Чтение с диска",
                    SensorUnit.BytesPerSecond,
                    "pdh.PhysicalDisk.Read",
                    1000));

                sensors.Add(new SensorDescriptor(
                    $"{disk.DeviceId}-write",
                    disk.DeviceId,
                    "disk.write.bytes_per_second",
                    "Запись на диск",
                    SensorUnit.BytesPerSecond,
                    "pdh.PhysicalDisk.Write",
                    1000));

                sensors.Add(new SensorDescriptor(
                    $"{disk.DeviceId}-busy",
                    disk.DeviceId,
                    "disk.busy.percent",
                    "Активность диска",
                    SensorUnit.Percent,
                    "pdh.PhysicalDisk.IdleTime",
                    1000));
            }

            return sensors;
        }
    }

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        var values = new Dictionary<string, ProviderValue>(StringComparer.Ordinal);
        IReadOnlyList<DiskInfo> disks;
        lock (_gate)
        {
            disks = _disks;
        }

        if (disks.Count == 0)
        {
            return values;
        }

        EnsureCounters();

        var read = _read?.Read() ?? [];
        var write = _write?.Read() ?? [];
        var idle = _idle?.Read() ?? [];
        var primed = _query?.IsPrimed == true;

        foreach (var disk in disks)
        {
            var readValue = Find(read, disk.Instance);
            var writeValue = Find(write, disk.Instance);
            var idleValue = Find(idle, disk.Instance);

            values[$"{disk.DeviceId}-read"] = readValue is null
                ? Missing(primed)
                : ProviderValue.Ok(readValue.Value);

            values[$"{disk.DeviceId}-write"] = writeValue is null
                ? Missing(primed)
                : ProviderValue.Ok(writeValue.Value);

            values[$"{disk.DeviceId}-busy"] = idleValue is null
                ? Missing(primed)
                : ProviderValue.Ok(Math.Clamp(100d - idleValue.Value, 0d, 100d));
        }

        return values;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _query?.Dispose();
            _query = null;
            _read = null;
            _write = null;
            _idle = null;
        }
    }

    private static ProviderValue Missing(bool primed) =>
        primed ? ProviderValue.Unavailable(SensorReason.NotApplicable) : ProviderValue.Waiting();

    private static double? Find(IReadOnlyList<PdhSample> samples, string instance)
    {
        foreach (var sample in samples)
        {
            if (string.Equals(sample.Instance, instance, StringComparison.OrdinalIgnoreCase))
            {
                return sample.Value;
            }
        }

        return null;
    }

    private void EnsureCounters()
    {
        lock (_gate)
        {
            if (_query is not null)
            {
                _query.Collect();
                return;
            }

            _query = new PdhQuery();
            if (!_query.IsOpen)
            {
                _query.Dispose();
                _query = null;
                return;
            }

            _read = _query.TryAdd(ReadPath, wildcard: true);
            _write = _query.TryAdd(WritePath, wildcard: true);
            _idle = _query.TryAdd(IdlePath, wildcard: true);

            if (_read is null || _write is null || _idle is null)
            {
                _query.Dispose();
                _query = null;
                _read = null;
                _write = null;
                _idle = null;
                return;
            }

            _query.Collect();
        }
    }

    private void RefreshDisks()
    {
        IReadOnlyList<string> instances = [];

        // Имена экземпляров появляются только после первого сбора счётчиков, поэтому
        // обнаружение делает до трёх попыток, а не ждёт следующего цикла каталога.
        for (var attempt = 0; attempt < 3 && instances.Count == 0; attempt++)
        {
            EnsureCounters();
            instances = _read?.ReadInstanceNames() ?? [];

            if (instances.Count == 0)
            {
                Thread.Sleep(150);
            }
        }
        var disks = new List<DiskInfo>();

        foreach (var instance in instances)
        {
            var match = DiskInstance().Match(instance);
            if (!match.Success)
            {
                continue;
            }

            var number = int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture);
            var drive = match.Groups[2].Value.Trim();
            var label = drive.Length > 0
                ? $"Диск {number} ({drive})"
                : $"Диск {number}";

            disks.Add(new DiskInfo($"disk-{number}", label, instance));
        }

        if (disks.Count == 0)
        {
            return;
        }

        lock (_gate)
        {
            if (_disks.Count == disks.Count && _disks.Zip(disks).All(pair => pair.First == pair.Second))
            {
                return;
            }

            _disks = disks;
        }
    }

    private sealed record DiskInfo(string DeviceId, string Label, string Instance);

    [GeneratedRegex(@"^(\d+)\s*(.*)$")]
    private static partial Regex DiskInstance();
}
