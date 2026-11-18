using System.Drawing;
using System.Windows.Forms;

namespace HardwareDisplay.Host.Desktop;

/// <summary>
/// Единственный постоянный интерфейс хоста: значок в трее и меню по правой кнопке.
/// Окно не требуется, сервер и сбор датчиков работают всё время, пока живёт процесс.
/// </summary>
internal sealed class TrayIcon : IDisposable
{
    private readonly HostShell _shell;
    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _serverItem;
    private readonly ToolStripMenuItem _phonesItem;
    private readonly ToolStripMenuItem _autostartItem;
    private readonly ToolStripMenuItem _firewallItem;

    public TrayIcon(HostShell shell)
    {
        _shell = shell;

        _statusItem = new ToolStripMenuItem("Сервер остановлен") { Enabled = false };
        _phonesItem = new ToolStripMenuItem("Телефоны");

        var openItem = new ToolStripMenuItem("Настройки и QR-код", null, (_, _) => _shell.ShowSettings());
        var copyItem = new ToolStripMenuItem("Копировать данные подключения", null, (_, _) => CopyInvite());
        _serverItem = new ToolStripMenuItem("Остановить сервер", null, (_, _) => Run(_shell.ToggleServerAsync));
        _autostartItem = new ToolStripMenuItem("Запускать при входе в Windows", null, (_, _) => ToggleAutostart());
        _firewallItem = new ToolStripMenuItem("Разрешить в брандмауэре Windows", null, (_, _) => ToggleFirewall());
        var exitItem = new ToolStripMenuItem("Выход", null, (_, _) => Run(_shell.ExitAsync));

        var menu = new ContextMenuStrip();
        menu.Items.AddRange(
        [
            openItem,
            _statusItem,
            new ToolStripSeparator(),
            copyItem,
            _serverItem,
            _phonesItem,
            new ToolStripSeparator(),
            _autostartItem,
            _firewallItem,
            new ToolStripSeparator(),
            exitItem,
        ]);
        menu.Opening += (_, _) =>
        {
            RefreshState();
            RefreshSystemState();
            RebuildPhones();
        };

        _icon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "HardwareDisplay",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => _shell.ShowSettings();

        RefreshState();
    }

    /// <summary>Строка состояния обновляется раз в секунду: это только текст, без системных вызовов.</summary>
    public void RefreshState()
    {
        var runtime = _shell.Runtime;
        var running = runtime.Server.IsRunning;
        var sessions = runtime.Server.ActiveSessions;

        _statusItem.Text = running
            ? $"Сервер работает · {Sessions(sessions)}"
            : "Сервер остановлен";
        _serverItem.Text = running ? "Остановить сервер" : "Запустить сервер";
        _icon.Text = running
            ? $"HardwareDisplay · {Sessions(sessions)}"
            : "HardwareDisplay · сервер остановлен";
    }

    public void Notify(string title, string text) =>
        _icon.ShowBalloonTip(4000, title, text, ToolTipIcon.Info);

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }

    private static string Sessions(int count) => count switch
    {
        0 => "нет подключений",
        1 => "1 телефон в эфире",
        _ => $"{count} телефона в эфире",
    };

    private static Icon LoadIcon()
    {
        var path = Environment.ProcessPath;
        if (path is not null)
        {
            var extracted = Icon.ExtractAssociatedIcon(path);
            if (extracted is not null)
            {
                return extracted;
            }
        }

        return SystemIcons.Application;
    }

    /// <summary>
    /// Состояние автозапуска и правила брандмауэра читается только при открытии меню и после
    /// изменения: проверка правила запускает netsh, а таймер обновляет значок раз в секунду.
    /// </summary>
    private void RefreshSystemState()
    {
        _autostartItem.Checked = Autostart.IsEnabled();

        var firewallPresent = FirewallRule.IsPresent();
        _firewallItem.Checked = firewallPresent;
        _firewallItem.Text = firewallPresent
            ? "Правило брандмауэра добавлено"
            : "Разрешить в брандмауэре Windows";
    }

    private void RebuildPhones()
    {
        var clients = _shell.Runtime.Pairing.Clients;
        _phonesItem.DropDownItems.Clear();

        if (clients.Count == 0)
        {
            _phonesItem.DropDownItems.Add(new ToolStripMenuItem("нет сопряжённых телефонов") { Enabled = false });
            return;
        }

        foreach (var client in clients)
        {
            var item = new ToolStripMenuItem($"Отозвать «{client.Name}»", null, (_, _) => Revoke(client.ClientId, client.Name));
            _phonesItem.DropDownItems.Add(item);
        }
    }

    private void Revoke(string clientId, string name)
    {
        _shell.Runtime.Pairing.Revoke(clientId);
        Notify("Доступ отозван", $"«{name}» больше не получает данные. Понадобится новое приглашение.");
        RefreshState();
    }

    private void CopyInvite()
    {
        var payload = _shell.Runtime.BuildInvitePayload();
        if (payload is null)
        {
            Notify("Данные подключения недоступны", "Сначала запустите сервер.");
            return;
        }

        try
        {
            System.Windows.Clipboard.SetText(payload);
            Notify("Данные подключения скопированы", "Вставьте их в приложении на телефоне.");
        }
        catch (Exception)
        {
            Notify("Не удалось скопировать", "Буфер обмена занят другим приложением.");
        }
    }

    private void ToggleAutostart()
    {
        var enable = !Autostart.IsEnabled();
        Autostart.Set(enable);
        RefreshSystemState();
        Notify(
            enable ? "Автозапуск включён" : "Автозапуск выключен",
            enable
                ? "Приложение запустится при входе в Windows и останется в трее."
                : "Автозапуск отключён.");
    }

    private void ToggleFirewall()
    {
        if (FirewallRule.IsPresent())
        {
            FirewallRule.TryRemove();
            Thread.Sleep(400);
            RefreshSystemState();
            Notify("Правило удалено", "Телефон больше не сможет подключиться без нового правила.");
            return;
        }

        if (!FirewallRule.TryAdd())
        {
            Notify("Не удалось добавить правило", "Windows отклонил запрос на повышение прав.");
            return;
        }

        Thread.Sleep(400);
        RefreshSystemState();
        Notify(
            FirewallRule.IsPresent() ? "Правило добавлено" : "Правило не добавлено",
            "Разрешён только этот файл, TCP 18443-18445, профиль Private, локальная подсеть.");
    }

    private static async void Run(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (Exception)
        {
            // Ошибка действия не должна ронять процесс из обработчика меню.
        }
    }
}
