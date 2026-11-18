using System.Diagnostics;
using System.IO;

namespace HardwareDisplay.Host.Desktop;

/// <summary>
/// Правило брандмауэра для входящих подключений телефона: только этот EXE, только TCP
/// портов сервера и только из локальной подсети. Добавление требует прав администратора,
/// поэтому выполняется отдельным запуском того же файла с ключом --firewall-add.
/// </summary>
internal static class FirewallRule
{
    public const string RuleName = "HardwareDisplay";
    public const string AddArgument = "--firewall-add";
    public const string RemoveArgument = "--firewall-remove";

    private const string Ports = "18443-18445";

    public static bool IsPresent() =>
        RunNetsh($"advfirewall firewall show rule name=\"{RuleName}\"") == 0;

    public static bool TryAdd() => RelaunchElevated(AddArgument);

    public static bool TryRemove() => RelaunchElevated(RemoveArgument);

    /// <summary>Выполняется в процессе, запущенном с повышением прав.</summary>
    public static int ApplyElevated(bool add)
    {
        return add
            ? RunNetsh(
                "advfirewall firewall add rule " +
                $"name=\"{RuleName}\" dir=in action=allow " +
                $"program=\"{ExecutablePath()}\" protocol=TCP localport={Ports} " +
                "profile=private,domain remoteip=localsubnet enable=yes")
            : RunNetsh($"advfirewall firewall delete rule name=\"{RuleName}\"");
    }

    private static bool RelaunchElevated(string argument)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = ExecutablePath(),
                Arguments = argument,
                UseShellExecute = true,
                Verb = "runas",
            });

            process?.WaitForExit(15000);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static string ExecutablePath() =>
        Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "HardwareDisplay.Host.exe");

    private static int RunNetsh(string arguments)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = "netsh",
                Arguments = arguments,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            });

            if (process is null)
            {
                return -1;
            }

            process.StandardOutput.ReadToEnd();
            process.StandardError.ReadToEnd();
            process.WaitForExit(15000);
            return process.ExitCode;
        }
        catch (Exception)
        {
            return -1;
        }
    }
}
