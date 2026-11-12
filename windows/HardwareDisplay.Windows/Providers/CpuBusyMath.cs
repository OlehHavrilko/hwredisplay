namespace HardwareDisplay.Windows.Providers;

public readonly record struct CpuTimes(ulong Idle, ulong Kernel, ulong User)
{
    public ulong Total => Kernel + User;
}

public static class CpuBusyMath
{
    /// <summary>
    /// Kernel уже включает idle, поэтому занятость считается как разность суммы kernel+user и idle.
    /// </summary>
    public static bool TryCompute(CpuTimes previous, CpuTimes current, out double busyPercent)
    {
        busyPercent = 0;

        if (current.Total <= previous.Total || current.Kernel < previous.Kernel || current.User < previous.User || current.Idle < previous.Idle)
        {
            return false;
        }

        var deltaTotal = current.Total - previous.Total;
        var deltaIdle = current.Idle - previous.Idle;

        if (deltaTotal == 0 || deltaIdle > deltaTotal)
        {
            return false;
        }

        busyPercent = 100.0 * (double)(deltaTotal - deltaIdle) / deltaTotal;
        return true;
    }
}
