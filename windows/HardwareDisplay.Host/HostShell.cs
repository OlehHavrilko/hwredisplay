using System.Windows;
using System.Windows.Threading;
using HardwareDisplay.Host.Desktop;
using HardwareDisplay.Host.Runtime;

namespace HardwareDisplay.Host;

/// <summary>
/// Оболочка хоста: живёт без окна. Держит сервер, датчики и значок в трее, а окно
/// создаёт только по требованию и освобождает при закрытии, чтобы в простое не было
/// ни WPF-поверхности, ни таймера интерфейса.
/// </summary>
public sealed class HostShell
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly TrayIcon _tray;
    private MainWindow? _window;

    public HostShell()
    {
        Runtime = AppRuntime.Create();
        _tray = new TrayIcon(this);
        _timer.Tick += (_, _) => _tray.RefreshState();
        _timer.Start();
    }

    public AppRuntime Runtime { get; }

    public bool HasWindow => _window is not null;

    public async Task InitializeAsync()
    {
        await Runtime.StartAsync().ConfigureAwait(true);
        _tray.RefreshState();
    }

    public void ShowSettings()
    {
        if (_window is null)
        {
            _window = new MainWindow(Runtime);
            _window.Closed += (_, _) =>
            {
                _window = null;
                _tray.RefreshState();
            };
        }

        _window.Show();
        _window.WindowState = WindowState.Normal;
        _window.Activate();
        _window.RefreshAll();
    }

    public async Task ToggleServerAsync()
    {
        if (Runtime.Server.IsRunning)
        {
            await Runtime.StopAsync().ConfigureAwait(true);
        }
        else
        {
            await Runtime.StartAsync().ConfigureAwait(true);
        }

        _tray.RefreshState();
        _window?.RefreshAll();
    }

    public async Task ExitAsync()
    {
        _timer.Stop();
        _tray.Dispose();

        await Runtime.StopAsync().ConfigureAwait(true);
        await Runtime.DisposeAsync().ConfigureAwait(true);

        _window?.Close();
        Application.Current.Shutdown();
    }
}
