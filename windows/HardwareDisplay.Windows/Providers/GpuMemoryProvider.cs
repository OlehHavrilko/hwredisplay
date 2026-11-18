using System.Globalization;
using System.Text.RegularExpressions;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;

namespace HardwareDisplay.Windows.Providers;

/// <summary>
/// Выделенная видеопамять: занятый объём из PDH по LUID адаптера, ёмкость — из DXGI.
/// Процент считается только там, где ёмкость известна и применима.
/// </summary>
public sealed partial class GpuMemoryProvider : ISensorProvider, IDisposable
{
    private const string DedicatedUsagePath = @"\GPU Adapter Memory(*)\Dedicated Usage";

    private readonly object _gate = new();
    private PdhQuery? _query;
    private PdhCounter? _counter;
    private IReadOnlyList<GpuAdapter> _adapters = [];

    public string Id => "pdh.GpuAdapterMemory";

    public TimeSpan PollInterval => TimeSpan.FromSeconds(1);

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices()
    {
        RefreshAdapters();
        lock (_gate)
        {
            return _adapters.Select(a => new DeviceDescriptor(a.DeviceId, DeviceKind.Gpu, a.Label)).ToArray();
        }
    }

    public IReadOnlyList<SensorDescriptor> DiscoverSensors()
    {
        RefreshAdapters();
        lock (_gate)
        {
            var sensors = new List<SensorDescriptor>(_adapters.Count * 3);
            foreach (var adapter in _adapters)
            {
                sensors.Add(new SensorDescriptor(
                    $"{adapter.DeviceId}-vram-used",
                    adapter.DeviceId,
                    "gpu.memory.dedicated_used.bytes",
                    "Занято видеопамяти",
                    SensorUnit.Bytes,
                    "pdh.GpuAdapterMemory",
                    1000));

                if (adapter.DedicatedVideoMemory <= 0)
                {
                    continue;
                }

                sensors.Add(new SensorDescriptor(
                    $"{adapter.DeviceId}-vram-total",
                    adapter.DeviceId,
                    "gpu.memory.dedicated_total.bytes",
                    "Всего видеопамяти",
                    SensorUnit.Bytes,
                    "dxgi.AdapterDesc1",
                    1000));

                sensors.Add(new SensorDescriptor(
                    $"{adapter.DeviceId}-vram-percent",
                    adapter.DeviceId,
                    "gpu.memory.dedicated_used.percent",
                    "Заполнение видеопамяти",
                    SensorUnit.Percent,
                    "pdh.GpuAdapterMemory+dxgi",
                    1000));
            }

            return sensors;
        }
    }

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        var values = new Dictionary<string, ProviderValue>(StringComparer.Ordinal);
        IReadOnlyList<GpuAdapter> adapters;
        lock (_gate)
        {
            adapters = _adapters;
        }

        if (adapters.Count == 0)
        {
            return values;
        }

        var counter = EnsureCounter();
        var used = new Dictionary<ulong, double>();

        if (counter is not null)
        {
            foreach (var sample in counter.Read())
            {
                if (sample.Instance is null)
                {
                    continue;
                }

                var match = MemoryInstance().Match(sample.Instance);
                if (!match.Success)
                {
                    continue;
                }

                var luid = ParseLuid(match.Groups[1].Value, match.Groups[2].Value);
                used[luid] = sample.Value;
            }
        }

        var primed = _query?.IsPrimed == true;

        foreach (var adapter in adapters)
        {
            if (adapter.DedicatedVideoMemory > 0)
            {
                values[$"{adapter.DeviceId}-vram-total"] = ProviderValue.Ok(adapter.DedicatedVideoMemory);
            }

            if (!used.TryGetValue(adapter.Luid, out var usedBytes))
            {
                values[$"{adapter.DeviceId}-vram-used"] = primed
                    ? ProviderValue.Unavailable(SensorReason.NotApplicable)
                    : ProviderValue.Waiting();
                continue;
            }

            values[$"{adapter.DeviceId}-vram-used"] = ProviderValue.Ok(usedBytes);

            if (adapter.DedicatedVideoMemory > 0)
            {
                var percent = 100d * usedBytes / adapter.DedicatedVideoMemory;
                values[$"{adapter.DeviceId}-vram-percent"] = ProviderValue.Ok(Math.Clamp(percent, 0d, 100d));
            }
        }

        return values;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _query?.Dispose();
            _query = null;
            _counter = null;
        }
    }

    private PdhCounter? EnsureCounter()
    {
        lock (_gate)
        {
            if (_counter is not null)
            {
                _query?.Collect();
                return _query?.IsPrimed == true ? _counter : null;
            }

            _query = new PdhQuery();
            if (!_query.IsOpen)
            {
                _query.Dispose();
                _query = null;
                return null;
            }

            _counter = _query.TryAdd(DedicatedUsagePath, wildcard: true);
            if (_counter is null)
            {
                _query.Dispose();
                _query = null;
                return null;
            }

            _query.Collect();
            return null;
        }
    }

    private void RefreshAdapters()
    {
        var adapters = GpuInventory.Enumerate();
        if (adapters.Count == 0)
        {
            return;
        }

        lock (_gate)
        {
            if (_adapters.Count == adapters.Count && _adapters.Zip(adapters).All(pair => pair.First == pair.Second))
            {
                return;
            }

            _adapters = adapters;
        }
    }

    private static ulong ParseLuid(string high, string low) => (ParseHex(high) << 32) | ParseHex(low);

    private static ulong ParseHex(string value)
    {
        var digits = value.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? value[2..] : value;
        return ulong.TryParse(digits, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : 0UL;
    }

    [GeneratedRegex(@"^luid_(0x[0-9a-fA-F]+)_(0x[0-9a-fA-F]+)_phys_\d+$")]
    private static partial Regex MemoryInstance();
}
