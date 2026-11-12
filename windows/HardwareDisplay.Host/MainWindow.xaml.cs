using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Model;
using HardwareDisplay.Host.Pairing;
using HardwareDisplay.Host.Runtime;
using QRCoder;

namespace HardwareDisplay.Host;

public partial class MainWindow : Window
{
    private readonly AppRuntime _runtime;
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly ObservableCollection<SensorRow> _sensors = [];
    private readonly ObservableCollection<ClientRow> _clients = [];
    private readonly Dictionary<string, SensorRow> _rowsBySensor = new(StringComparer.Ordinal);
    private bool _closing;

    public MainWindow()
    {
        InitializeComponent();

        _runtime = AppRuntime.Create();

        SensorList.ItemsSource = _sensors;
        ClientList.ItemsSource = _clients;

        DataPathText.Text = "Данные: " + _runtime.DataDirectory;
        HostNameText.Text = _runtime.HostName;
        FingerprintText.Text = _runtime.Identity.FingerprintSha256;

        Loaded += OnLoadedAsync;
        _timer.Tick += OnTick;
    }

    private async void OnLoadedAsync(object sender, RoutedEventArgs e)
    {
        await StartServerAsync().ConfigureAwait(true);
        _timer.Start();
    }

    private async Task StartServerAsync()
    {
        var started = await _runtime.StartAsync().ConfigureAwait(true);

        if (!started)
        {
            DiagnosticText.Text = _runtime.Diagnostic;
            ToggleServerButton.Content = "Запустить сервер";
            UpdateServerState();
            return;
        }

        DiagnosticText.Text = string.Empty;
        ToggleServerButton.Content = "Остановить сервер";
        UpdateServerState();
        RenderInvite();
    }

    private void OnTick(object? sender, EventArgs e)
    {
        UpdateServerState();
        UpdateInvite();
        UpdateSensors();
        UpdateClients();
    }

    private void UpdateServerState()
    {
        var running = _runtime.Server.IsRunning;

        ServerDot.Fill = (Brush)FindResource(running ? "Accent" : "Warn");
        ServerStateText.Text = running ? "Сервер работает" : "Сервер остановлен";

        AddressText.Text = running && _runtime.Address is { } address
            ? $"{address}:{_runtime.Port}"
            : "—";

        var sessions = _runtime.Server.ActiveSessions;
        SessionsText.Text = sessions == 0
            ? "нет подключений"
            : sessions == 1 ? "1 телефон в эфире" : $"{sessions} телефона в эфире";

        CollectorStateText.Text = _runtime.Collector.Latest is { } frame
            ? $"замеров: {frame.Snapshot.Seq}, обновление {_runtime.Collector.SamplePeriodMs} мс"
            : "ждём первый замер";
    }

    private void UpdateInvite()
    {
        var invite = _runtime.Pairing.CurrentInvite;

        if (!_runtime.Server.IsRunning)
        {
            InviteTimerText.Text = string.Empty;
            return;
        }

        if (invite is null || invite.IsExpired)
        {
            RenderInvite();
            return;
        }

        var remaining = invite.Remaining;
        InviteTimerText.Text = remaining.TotalSeconds > 0
            ? $"приглашение действует {remaining.Minutes}:{remaining.Seconds:00}"
            : "приглашение истекло";
    }

    private void RenderInvite()
    {
        if (!_runtime.Server.IsRunning)
        {
            QrImage.Source = null;
            InviteText.Text = "—";
            InviteTimerText.Text = string.Empty;
            return;
        }

        var invite = _runtime.RefreshInvite();
        InviteText.Text = invite.Code;

        var payload = InvitePayload.Create(
            _runtime.Identity.HostIdText,
            _runtime.Address?.ToString() ?? string.Empty,
            _runtime.Port,
            _runtime.Identity.FingerprintSha256,
            invite.Code);

        QrImage.Source = RenderQr(payload.ToJson());
        InviteTimerText.Text = "приглашение действует " + invite.Remaining.ToString(@"m\:ss", CultureInfo.InvariantCulture);
    }

    private static BitmapImage? RenderQr(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M, forceUtf8: true);
        var png = new PngByteQRCode(data).GetGraphic(8);

        var image = new BitmapImage();
        image.BeginInit();
        image.StreamSource = new MemoryStream(png);
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void UpdateSensors()
    {
        var frame = _runtime.Collector.Latest;
        if (frame is null)
        {
            return;
        }

        var byId = frame.Snapshot.Samples.ToDictionary(s => s.SensorId, StringComparer.Ordinal);
        var byDeviceAndMetric = frame.Catalog.Sensors
            .GroupBy(s => (s.DeviceId, s.MetricKey))
            .ToDictionary(g => g.Key, g => g.First().SensorId);
        var deviceLabels = frame.Catalog.Devices.ToDictionary(d => d.DeviceId, d => d.Label, StringComparer.Ordinal);

        foreach (var descriptor in frame.Catalog.Sensors)
        {
            if (!_rowsBySensor.TryGetValue(descriptor.SensorId, out var row))
            {
                row = new SensorRow(descriptor.SensorId, descriptor.Label);
                _rowsBySensor[descriptor.SensorId] = row;
                _sensors.Add(row);
            }

            row.Detail = deviceLabels.TryGetValue(descriptor.DeviceId, out var deviceLabel)
                ? deviceLabel
                : descriptor.DeviceId;

            if (!byId.TryGetValue(descriptor.SensorId, out var reading))
            {
                row.Display = "—";
                row.Detail = row.Detail + " · нет в снимке";
                continue;
            }

            if (reading.Status != SensorStatus.Ok || reading.Value is null)
            {
                row.Display = "—";
                row.Detail = $"{row.Detail} · {StatusText(reading.Reason)}";
                continue;
            }

            row.Display = FormatValue(descriptor, reading.Value.Value, frame, byDeviceAndMetric, byId);
        }
    }

    private static string FormatValue(
        SensorDescriptor descriptor,
        double value,
        TelemetryFrame frame,
        IReadOnlyDictionary<(string, string), string> byDeviceAndMetric,
        IReadOnlyDictionary<string, SensorReading> byId)
    {
        switch (descriptor.Unit)
        {
            case SensorUnit.Percent:
                return value.ToString("0.0", CultureInfo.CurrentCulture) + " %";

            case SensorUnit.Bytes:
                if (descriptor.MetricKey == "memory.used.bytes" &&
                    byDeviceAndMetric.TryGetValue((descriptor.DeviceId, "memory.total.bytes"), out var totalId) &&
                    byId.TryGetValue(totalId, out var total) &&
                    total.Value is { } totalValue)
                {
                    return $"{FormatGiB(value)} / {FormatGiB(totalValue)}";
                }

                return FormatBytes(value);

            case SensorUnit.BytesPerSecond:
                return FormatBytes(value) + "/с";

            case SensorUnit.Celsius:
                return value.ToString("0.0", CultureInfo.CurrentCulture) + " °C";

            case SensorUnit.Hertz:
                return (value / 1000.0).ToString("0", CultureInfo.CurrentCulture) + " МГц";

            case SensorUnit.Watt:
                return value.ToString("0", CultureInfo.CurrentCulture) + " Вт";

            case SensorUnit.Volt:
                return value.ToString("0.00", CultureInfo.CurrentCulture) + " В";

            case SensorUnit.Rpm:
                return value.ToString("0", CultureInfo.CurrentCulture) + " об/мин";

            default:
                return value.ToString("0.##", CultureInfo.CurrentCulture);
        }
    }

    private static string FormatGiB(double bytes) =>
        (bytes / 1024d / 1024d / 1024d).ToString("0.0", CultureInfo.CurrentCulture) + " ГиБ";

    private static string FormatBytes(double bytes) => bytes switch
    {
        >= 1024d * 1024d * 1024d => FormatGiB(bytes),
        >= 1024d * 1024d => (bytes / 1024d / 1024d).ToString("0", CultureInfo.CurrentCulture) + " МиБ",
        >= 1024d => (bytes / 1024d).ToString("0", CultureInfo.CurrentCulture) + " КиБ",
        _ => bytes.ToString("0", CultureInfo.CurrentCulture) + " Б",
    };

    private static string StatusText(string? reason) => reason switch
    {
        SensorReason.Warmup => "ждём первый замер",
        SensorReason.Unsupported => "датчик не поддерживается",
        SensorReason.NotApplicable => "неприменимо на этом ПК",
        SensorReason.PermissionDenied => "нужны дополнительные права",
        SensorReason.ProviderOffline => "источник недоступен",
        SensorReason.ReadFailed => "ошибка чтения",
        SensorReason.TimedOut => "источник не ответил",
        SensorReason.Expired => "значение устарело",
        null => "нет значения",
        _ => reason,
    };

    private void UpdateClients()
    {
        var clients = _runtime.Pairing.Clients;

        if (clients.Count == _clients.Count && clients.All(c => _clients.Any(row => row.ClientId == c.ClientId)))
        {
            return;
        }

        _clients.Clear();
        foreach (var client in clients)
        {
            _clients.Add(new ClientRow(
                client.ClientId,
                client.Name,
                "сопряжён " + client.PairedAtUtc.ToLocalTime().ToString("dd.MM.yyyy HH:mm", CultureInfo.CurrentCulture)));
        }

        EmptyClientsText.Visibility = _clients.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void OnToggleServer(object sender, RoutedEventArgs e)
    {
        if (_runtime.Server.IsRunning)
        {
            await _runtime.StopAsync().ConfigureAwait(true);
            ToggleServerButton.Content = "Запустить сервер";
            QrImage.Source = null;
            InviteText.Text = "—";
            InviteTimerText.Text = string.Empty;
            DiagnosticText.Text = "Сервер остановлен. Телефоны не получают данные.";
        }
        else
        {
            await StartServerAsync().ConfigureAwait(true);
        }

        UpdateServerState();
    }

    private void OnRefreshInvite(object sender, RoutedEventArgs e)
    {
        if (!_runtime.Server.IsRunning)
        {
            DiagnosticText.Text = "Сначала запустите сервер.";
            return;
        }

        RenderInvite();
    }

    private void OnRevokeClient(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string clientId)
        {
            return;
        }

        _runtime.Pairing.Revoke(clientId);
        DiagnosticText.Text = "Доступ телефона отозван, его поток закрыт.";
        UpdateClients();
    }

    protected override async void OnClosing(CancelEventArgs e)
    {
        if (_closing)
        {
            base.OnClosing(e);
            return;
        }

        e.Cancel = true;
        _closing = true;
        _timer.Stop();

        await _runtime.StopAsync().ConfigureAwait(true);
        await _runtime.DisposeAsync().ConfigureAwait(true);

        Close();
    }
}

public sealed class SensorRow(string sensorId, string label) : INotifyPropertyChanged
{
    private string _display = "—";
    private string _detail = string.Empty;

    public event PropertyChangedEventHandler? PropertyChanged;

    public string SensorId { get; } = sensorId;

    public string Label { get; } = label;

    public string Display
    {
        get => _display;
        set => Set(ref _display, value);
    }

    public string Detail
    {
        get => _detail;
        set => Set(ref _detail, value);
    }

    private void Set(ref string field, string value, [CallerMemberName] string? name = null)
    {
        if (field == value)
        {
            return;
        }

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public sealed class ClientRow(string clientId, string name, string detail)
{
    public string ClientId { get; } = clientId;

    public string Name { get; } = name;

    public string Detail { get; } = detail;
}
