using System.Diagnostics;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Core.Providers;

namespace HardwareDisplay.Core.Collection;

public sealed record TelemetryCollectorOptions
{
    public TimeSpan TickInterval { get; init; } = TimeSpan.FromSeconds(1);

    public TimeSpan ProviderTimeout { get; init; } = TimeSpan.FromSeconds(2);

    public TimeSpan CatalogRefreshInterval { get; init; } = TimeSpan.FromSeconds(10);
}

public sealed record TelemetryFrame(Catalog Catalog, Snapshot Snapshot);

/// <summary>
/// Один цикл опроса обслуживает все будущие подключения: провайдеры опрашиваются
/// независимо от числа клиентов, каталог публикуется вместе со снимком одной ревизии.
/// </summary>
public sealed class TelemetryCollector : IAsyncDisposable
{
    private readonly ProviderState[] _states;
    private readonly TelemetryCollectorOptions _options;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly object _gate = new();
    private readonly CancellationTokenSource _cts = new();

    private Catalog _catalog = new(0, [], []);
    private long _seq;
    private long? _lastCatalogRefreshMs;
    private Task? _loop;
    private volatile TelemetryFrame? _latest;

    public TelemetryCollector(IEnumerable<ISensorProvider> providers, TelemetryCollectorOptions? options = null)
    {
        _options = options ?? new TelemetryCollectorOptions();
        _states = providers.Select(p => new ProviderState(p)).ToArray();
        if (_states.Length == 0)
        {
            throw new ArgumentException("Нужен хотя бы один провайдер датчиков.", nameof(providers));
        }

        DiscoverSensors();
    }

    public Guid StreamEpoch { get; } = Guid.NewGuid();

    public int SamplePeriodMs => (int)_options.TickInterval.TotalMilliseconds;

    public Catalog CurrentCatalog
    {
        get { lock (_gate) { return _catalog; } }
    }

    public TelemetryFrame? Latest => _latest;

    public event Action<TelemetryFrame>? FramePublished;

    public void Start()
    {
        _loop ??= Task.Run(() => RunAsync(_cts.Token));
    }

    public async Task StopAsync()
    {
        await _cts.CancelAsync().ConfigureAwait(false);
        if (_loop is not null)
        {
            try
            {
                await _loop.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        foreach (var state in _states)
        {
            if (state.Provider is IDisposable disposable)
            {
                disposable.Dispose();
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _cts.Dispose();
    }

    private async Task RunAsync(CancellationToken token)
    {
        using var timer = new PeriodicTimer(_options.TickInterval);
        while (!token.IsCancellationRequested)
        {
            try
            {
                await timer.WaitForNextTickAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            var monotonicMs = _clock.ElapsedMilliseconds;
            if (_lastCatalogRefreshMs is null || monotonicMs - _lastCatalogRefreshMs >= _options.CatalogRefreshInterval.TotalMilliseconds)
            {
                _lastCatalogRefreshMs = monotonicMs;
                DiscoverSensors();
            }

            StartDuePolls(monotonicMs);
            Publish(monotonicMs);
        }
    }

    private void DiscoverSensors()
    {
        var devices = new Dictionary<string, DeviceDescriptor>(StringComparer.Ordinal);
        var sensors = new List<SensorDescriptor>();

        foreach (var state in _states)
        {
            IReadOnlyList<DeviceDescriptor> providerDevices;
            IReadOnlyList<SensorDescriptor> providerSensors;
            try
            {
                providerDevices = state.Provider.DiscoverDevices();
                providerSensors = state.Provider.DiscoverSensors();
            }
            catch (Exception)
            {
                providerDevices = [];
                providerSensors = [];
            }

            foreach (var device in providerDevices)
            {
                devices[device.DeviceId] = device;
            }

            state.SensorIds = providerSensors.Select(s => s.SensorId).ToHashSet(StringComparer.Ordinal);
            sensors.AddRange(providerSensors);
        }

        foreach (var sensor in sensors)
        {
            if (!devices.ContainsKey(sensor.DeviceId))
            {
                devices[sensor.DeviceId] = new DeviceDescriptor(sensor.DeviceId, "unknown", sensor.DeviceId);
            }
        }

        var orderedDevices = devices.Values.OrderBy(d => d.DeviceId, StringComparer.Ordinal).ToArray();
        var orderedSensors = sensors.OrderBy(s => s.SensorId, StringComparer.Ordinal).ToArray();

        lock (_gate)
        {
            if (!CatalogEquals(_catalog, orderedDevices, orderedSensors))
            {
                _catalog = new Catalog(_catalog.Revision + 1, orderedDevices, orderedSensors);
            }
        }
    }

    private void StartDuePolls(long monotonicMs)
    {
        foreach (var state in _states)
        {
            if (state.Inner is { IsCompleted: false })
            {
                continue;
            }

            if (state.NextDueMs is not null && monotonicMs < state.NextDueMs)
            {
                continue;
            }

            state.NextDueMs = monotonicMs + (long)state.Provider.PollInterval.TotalMilliseconds;
            state.Inner = PollProviderAsync(state, monotonicMs);
        }
    }

    private async Task PollProviderAsync(ProviderState state, long requestedAtMs)
    {
        var poll = Task.Run(state.Provider.Poll);
        try
        {
            var values = await poll.WaitAsync(_options.ProviderTimeout).ConfigureAwait(false);
            lock (_gate)
            {
                state.Values = values;
                state.LastPollMs = _clock.ElapsedMilliseconds;
                state.Outcome = ProviderOutcome.Ok;
                state.Reason = null;
            }
        }
        catch (TimeoutException)
        {
            Observe(poll);
            lock (_gate)
            {
                state.Outcome = ProviderOutcome.TimedOut;
                state.Reason = SensorReason.TimedOut;
                state.LastPollMs = requestedAtMs;
            }
        }
        catch (Exception)
        {
            lock (_gate)
            {
                state.Outcome = ProviderOutcome.Failed;
                state.Reason = SensorReason.ReadFailed;
                state.Values = new Dictionary<string, ProviderValue>(StringComparer.Ordinal);
            }
        }
    }

    private static void Observe(Task task) =>
        task.ContinueWith(static t => _ = t.Exception, CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted, TaskScheduler.Default);

    private void Publish(long monotonicMs)
    {
        TelemetryFrame frame;
        lock (_gate)
        {
            var catalog = _catalog;
            var samples = new List<SensorReading>(catalog.Sensors.Count);

            foreach (var sensor in catalog.Sensors)
            {
                var state = FindState(sensor.SensorId);
                samples.Add(ComposeReading(sensor, state, monotonicMs));
            }

            var snapshot = new Snapshot(
                StreamEpoch,
                catalog.Revision,
                ++_seq,
                DateTimeOffset.UtcNow,
                monotonicMs,
                samples);

            frame = new TelemetryFrame(catalog, snapshot);
        }

        _latest = frame;
        FramePublished?.Invoke(frame);
    }

    private SensorReading ComposeReading(SensorDescriptor sensor, ProviderState? state, long monotonicMs)
    {
        if (state is null)
        {
            return new SensorReading(sensor.SensorId, SensorStatus.Error, null, SensorReason.ProviderOffline, null);
        }

        if (state.Outcome == ProviderOutcome.TimedOut)
        {
            return new SensorReading(sensor.SensorId, SensorStatus.Stale, null, state.Reason ?? SensorReason.TimedOut, null);
        }

        if (state.Values.TryGetValue(sensor.SensorId, out var value))
        {
            if (value.Status == SensorStatus.Ok)
            {
                if (value.Value is null || !double.IsFinite(value.Value.Value))
                {
                    return new SensorReading(sensor.SensorId, SensorStatus.Error, null, SensorReason.ReadFailed, null);
                }

                var age = Math.Clamp(monotonicMs - state.LastPollMs, 0, monotonicMs);
                return new SensorReading(sensor.SensorId, SensorStatus.Ok, value.Value, null, age);
            }

            return new SensorReading(sensor.SensorId, NormalizeStatus(value.Status), null, value.Reason, null);
        }

        return state.Outcome == ProviderOutcome.Failed
            ? new SensorReading(sensor.SensorId, SensorStatus.Error, null, state.Reason ?? SensorReason.ReadFailed, null)
            : new SensorReading(sensor.SensorId, SensorStatus.Waiting, null, SensorReason.Warmup, null);
    }

    private static string NormalizeStatus(string status) => status switch
    {
        SensorStatus.Waiting => SensorStatus.Waiting,
        SensorStatus.Unavailable => SensorStatus.Unavailable,
        SensorStatus.Error => SensorStatus.Error,
        SensorStatus.Stale => SensorStatus.Stale,
        _ => SensorStatus.Error,
    };

    private ProviderState? FindState(string sensorId)
    {
        foreach (var state in _states)
        {
            if (state.SensorIds.Contains(sensorId))
            {
                return state;
            }
        }

        return null;
    }

    private static bool CatalogEquals(Catalog current, IReadOnlyList<DeviceDescriptor> devices, IReadOnlyList<SensorDescriptor> sensors)
    {
        if (current.Devices.Count != devices.Count || current.Sensors.Count != sensors.Count)
        {
            return false;
        }

        for (var i = 0; i < devices.Count; i++)
        {
            if (current.Devices[i] != devices[i])
            {
                return false;
            }
        }

        for (var i = 0; i < sensors.Count; i++)
        {
            if (current.Sensors[i] != sensors[i])
            {
                return false;
            }
        }

        return true;
    }

    private enum ProviderOutcome
    {
        None,
        Ok,
        Failed,
        TimedOut,
    }

    private sealed class ProviderState(ISensorProvider provider)
    {
        public ISensorProvider Provider { get; } = provider;

        public HashSet<string> SensorIds { get; set; } = new(StringComparer.Ordinal);

        public IReadOnlyDictionary<string, ProviderValue> Values { get; set; } =
            new Dictionary<string, ProviderValue>(StringComparer.Ordinal);

        public ProviderOutcome Outcome { get; set; }

        public string? Reason { get; set; }

        public long LastPollMs { get; set; }

        public long? NextDueMs { get; set; }

        public Task? Inner { get; set; }
    }
}
