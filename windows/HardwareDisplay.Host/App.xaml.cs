using System.Windows;
using HardwareDisplay.Host.Desktop;

namespace HardwareDisplay.Host;

public partial class App : Application
{
    /// <summary>Запуск с автозагрузки: окно не открывается, хост живёт в трее.</summary>
    public static bool StartInTray { get; private set; }

    protected override async void OnStartup(StartupEventArgs e)
    {
        // Повышенный запуск нужен только для правила брандмауэра: он выполняет netsh и выходит.
        if (e.Args.Contains(FirewallRule.AddArgument))
        {
            Shutdown(FirewallRule.ApplyElevated(add: true));
            return;
        }

        if (e.Args.Contains(FirewallRule.RemoveArgument))
        {
            Shutdown(FirewallRule.ApplyElevated(add: false));
            return;
        }

        StartInTray = e.Args.Contains("--tray");

        base.OnStartup(e);

        var shell = new HostShell();
        await shell.InitializeAsync().ConfigureAwait(true);

        // Окно открывается только на первом запуске или по явному запросу: без сопряжённых
        // телефонов пользователю нужен QR-код, в остальных случаях достаточно значка.
        var firstRun = shell.Runtime.Pairing.Clients.Count == 0;
        if (firstRun || e.Args.Contains("--window"))
        {
            shell.ShowSettings();
        }
    }
}
