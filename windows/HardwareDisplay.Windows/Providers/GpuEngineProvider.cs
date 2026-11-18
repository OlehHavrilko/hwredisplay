using System.Globalization;
using System.Text.RegularExpressions;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;

namespace HardwareDisplay.Windows.Providers;

/// <summary>
/// Загрузка графического чипа по счётчику `GPU Engine`. Счётчик даёт вклад каждого процесса
/// в каждый движок; сумма по процессам внутри движка и максимум по движкам повторяют
/// логику диспетчера задач Windows.
/// </summary>
public sealed partial class GpuEngineProvider : ISensorProvider, IDisposable
{
    private const string CounterPath = @"\GPU Engine(*)\Utilization Percentage";

    private readonly object _gate = new();
    private PdhQuery? _query;
    private PdhCounter? _counter;
    private IReadOnlyList<GpuAdapter> _adapters = [];

    public string Id => "pdh.GpuEngine";

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
            return _adapters
                .Select(a => new SensorDescriptor(
                    $"{a.DeviceId}-busy",
                    a.DeviceId,
                    "gpu.utilization.percent",
                    "Загрузка графического чипа",
                    SensorUnit.Percent,
                    "pdh.GpuEngine",
                    1000))
                .ToArray();
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
        if (counter is null)
        {
            foreach (var adapter in adapters)
            {
                values[$"{adapter.DeviceId}-busy"] = ProviderValue.Unavailable(SensorReason.Unsupported);
            }

            return values;
        }

        var samples = counter.Read();
        if (samples.Count == 0)
        {
            foreach (var adapter in adapters)
            {
                values[$"{adapter.DeviceId}-busy"] = ProviderValue.Waiting();
            }

            return values;
        }

        var perAdapterEngine = new Dictionary<ulong, Dictionary<string, double>>();
        foreach (var sample in samples)
        {
            if (sample.Instance is null)
            {
                continue;
            }

            var match = EngineInstance().Match(sample.Instance);
            if (!match.Success)
            {
                continue;
            }

            var luid = ParseLuid(match.Groups[1].Value, match.Groups[2].Value);
            var engineKey = string.Join(
                '_',
                match.Groups[3].Value,
                match.Groups[4].Value,
                match.Groups[5].Value);

            if (!perAdapterEngine.TryGetValue(luid, out var engines))
            {
                engines = new Dictionary<string, double>(StringComparer.Ordinal);
                perAdapterEngine[luid] = engines;
            }

            engines.TryGetValue(engineKey, out var current);
            engines[engineKey] = current + sample.Value;
        }

        foreach (var adapter in adapters)
        {
            if (!perAdapterEngine.TryGetValue(adapter.Luid, out var engines) || engines.Count == 0)
            {
                values[$"{adapter.DeviceId}-busy"] = ProviderValue.Waiting();
                continue;
            }

            var busiest = engines.Values.Max();
            values[$"{adapter.DeviceId}-busy"] = ProviderValue.Ok(Math.Clamp(busiest, 0d, 100d));
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

            _counter = _query.TryAdd(CounterPath, wildcard: true);
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
            if (_adapters.Count == adapters.Count &&
                _adapters.Zip(adapters).All(pair => pair.First == pair.Second))
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

    [GeneratedRegex(@"^pid_\d+_luid_(0x[0-9a-fA-F]+)_(0x[0-9a-fA-F]+)_phys_(\d+)_eng_(\d+)_engtype_(.+)$")]
    private static partial Regex EngineInstance();
}
