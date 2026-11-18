using System.Text;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;
using LibreHardwareMonitor.Hardware;

namespace HardwareDisplay.Windows.Providers;

/// <summary>
/// Температуры и вентиляторы через LibreHardwareMonitor. Библиотека работает в обычном
/// пользовательском процессе: если датчику нужны права выше или драйвер недоступен,
/// провайдер молча отдаёт меньше датчиков, а не роняет сбор. Управление железом не используется.
/// </summary>
public sealed class HardwareMonitorProvider : ISensorProvider, IDisposable
{
    private static readonly (SensorType Type, string Metric, string Unit)[] TrackedSensors =
    [
        (SensorType.Temperature, "temperature", SensorUnit.Celsius),
        (SensorType.Fan, "fan", SensorUnit.Rpm),
    ];

    private readonly object _gate = new();
    private readonly List<HardwareEntry> _entries = [];
    private Computer? _computer;
    private string? _failureReason;

    public string Id => "lhm.0.9.6";

    public TimeSpan PollInterval => TimeSpan.FromSeconds(1);

    /// <summary>Причина, по которой группа недоступна: показывается в окне хоста.</summary>
    public string? FailureReason
    {
        get
        {
            lock (_gate)
            {
                return _failureReason;
            }
        }
    }

    public IReadOnlyList<DeviceDescriptor> DiscoverDevices()
    {
        lock (_gate)
        {
            EnsureOpened();
            return _entries
                .Select(e => new DeviceDescriptor(e.DeviceId, e.Kind, e.Label))
                .ToArray();
        }
    }

    public IReadOnlyList<SensorDescriptor> DiscoverSensors()
    {
        lock (_gate)
        {
            EnsureOpened();
            return _entries.SelectMany(e => e.Sensors.Select(s => s.Descriptor)).ToArray();
        }
    }

    public IReadOnlyDictionary<string, ProviderValue> Poll()
    {
        var values = new Dictionary<string, ProviderValue>(StringComparer.Ordinal);

        lock (_gate)
        {
            if (_computer is null)
            {
                return values;
            }

            try
            {
                foreach (var entry in _entries)
                {
                    entry.Hardware.Update();
                    entry.Hardware.SubHardware.ToList().ForEach(sub => sub.Update());
                }
            }
            catch (Exception)
            {
                _failureReason = SensorReason.ProviderOffline;
                foreach (var entry in _entries)
                {
                    foreach (var sensor in entry.Sensors)
                    {
                        values[sensor.Descriptor.SensorId] = ProviderValue.Error(SensorReason.ReadFailed);
                    }
                }

                return values;
            }

            foreach (var entry in _entries)
            {
                foreach (var tracked in entry.Sensors)
                {
                    var value = tracked.Sensor.Value;
                    if (value is null)
                    {
                        values[tracked.Descriptor.SensorId] = ProviderValue.Unavailable(SensorReason.NotApplicable);
                        continue;
                    }

                    // Ноль градусов у работающего ПК — не показание, а недоступный датчик:
                    // LHM отдаёт 0, когда чтение через драйвер не удалось. Вентилятор
                    // при этом может стоять, поэтому правило действует только для температур.
                    if (tracked.Descriptor.Unit == SensorUnit.Celsius && value.Value <= 0.05f)
                    {
                        values[tracked.Descriptor.SensorId] = ProviderValue.Unavailable(SensorReason.Unsupported);
                        continue;
                    }

                    values[tracked.Descriptor.SensorId] = ProviderValue.Ok(Math.Round(value.Value, 1));
                }
            }
        }

        return values;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            try
            {
                _computer?.Close();
            }
            catch (Exception)
            {
                // Закрытие драйвера не должно мешать остановке приложения.
            }

            _computer = null;
            _entries.Clear();
        }
    }

    private void EnsureOpened()
    {
        if (_computer is not null || _failureReason is not null)
        {
            return;
        }

        try
        {
            var computer = new Computer
            {
                IsCpuEnabled = true,
                IsGpuEnabled = true,
                IsMotherboardEnabled = true,
                IsStorageEnabled = true,
                IsMemoryEnabled = false,
                IsNetworkEnabled = false,
                IsControllerEnabled = false,
            };

            computer.Open();
            _computer = computer;
            Discover(computer);
        }
        catch (Exception)
        {
            _computer = null;
            _failureReason = SensorReason.PermissionDenied;
        }
    }

    private void Discover(Computer computer)
    {
        var index = 0;

        foreach (var hardware in computer.Hardware)
        {
            AddEntry(hardware, ref index);
        }
    }

    private void AddEntry(IHardware hardware, ref int index)
    {
        index++;
        var kind = KindOf(hardware.HardwareType);
        if (kind is null)
        {
            return;
        }

        var deviceId = $"lhm-{Slug(kind)}-{index}";
        var entry = new HardwareEntry(deviceId, kind, hardware.Name, hardware, []);
        var usedKeys = new HashSet<string>(StringComparer.Ordinal);
        var sensorIndex = 0;

        foreach (var sensor in hardware.Sensors)
        {
            var tracked = TrackedSensors.FirstOrDefault(t => t.Type == sensor.SensorType);
            if (tracked.Metric is null || sensor.Name is null)
            {
                continue;
            }

            sensorIndex++;
            var slug = Slug(sensor.Name);
            var metricKey = $"{kind}.{tracked.Metric}.{slug}";
            if (!usedKeys.Add(metricKey))
            {
                metricKey = $"{metricKey}-{sensorIndex}";
                usedKeys.Add(metricKey);
            }

            var descriptor = new SensorDescriptor(
                $"{deviceId}-{tracked.Metric}-{sensorIndex}",
                deviceId,
                metricKey,
                sensor.Name,
                tracked.Unit,
                "lhm." + hardware.HardwareType,
                1000);

            entry.Sensors.Add(new TrackedSensor(sensor, descriptor));
        }

        if (entry.Sensors.Count == 0)
        {
            return;
        }

        _entries.Add(entry);
        UpdateSubHardware(hardware, deviceId, kind, entry, ref sensorIndex);
    }

    private void UpdateSubHardware(IHardware hardware, string deviceId, string kind, HardwareEntry entry, ref int sensorIndex)
    {
        foreach (var sub in hardware.SubHardware)
        {
            foreach (var sensor in sub.Sensors)
            {
                var tracked = TrackedSensors.FirstOrDefault(t => t.Type == sensor.SensorType);
                if (tracked.Metric is null || sensor.Name is null)
                {
                    continue;
                }

                sensorIndex++;
                var descriptor = new SensorDescriptor(
                    $"{deviceId}-{tracked.Metric}-{sensorIndex}",
                    deviceId,
                    $"{kind}.{tracked.Metric}.{Slug(sensor.Name)}",
                    sensor.Name,
                    tracked.Unit,
                    "lhm." + sub.HardwareType,
                    1000);

                entry.Sensors.Add(new TrackedSensor(sensor, descriptor));
            }
        }
    }

    private static string? KindOf(HardwareType type) => type switch
    {
        HardwareType.Cpu => DeviceKind.Cpu,
        HardwareType.GpuNvidia or HardwareType.GpuAmd or HardwareType.GpuIntel => DeviceKind.Gpu,
        HardwareType.Motherboard or HardwareType.SuperIO => DeviceKind.Motherboard,
        HardwareType.Storage => DeviceKind.Storage,
        _ => null,
    };

    private static string Slug(string text)
    {
        var builder = new StringBuilder(text.Length);
        var lastWasSeparator = false;

        foreach (var character in text)
        {
            if (char.IsLetterOrDigit(character))
            {
                builder.Append(char.ToLowerInvariant(character));
                lastWasSeparator = false;
            }
            else if (!lastWasSeparator && builder.Length > 0)
            {
                builder.Append('_');
                lastWasSeparator = true;
            }
        }

        return builder.ToString().TrimEnd('_');
    }

    private sealed record TrackedSensor(ISensor Sensor, SensorDescriptor Descriptor);

    private sealed class HardwareEntry(
        string deviceId,
        string kind,
        string label,
        IHardware hardware,
        List<TrackedSensor> sensors)
    {
        public string DeviceId { get; } = deviceId;

        public string Kind { get; } = kind;

        public string Label { get; } = label;

        public IHardware Hardware { get; } = hardware;

        public List<TrackedSensor> Sensors { get; } = sensors;
    }
}
