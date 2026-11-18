using Microsoft.Win32;

namespace HardwareDisplay.Host.Desktop;

/// <summary>
/// Автозапуск при входе в Windows — ключ Run текущего пользователя: он не требует прав
/// администратора и не создаёт службу, которая работала бы без сеанса пользователя.
/// </summary>
internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "HardwareDisplay";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
        return key?.GetValue(ValueName) is string value && value.Length > 0;
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (key is null)
        {
            return;
        }

        if (enabled)
        {
            key.SetValue(ValueName, BuildCommand(), RegistryValueKind.String);
        }
        else
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }

    private static string BuildCommand()
    {
        var executable = Environment.ProcessPath ?? string.Empty;
        return $"\"{executable}\" --tray";
    }
}
