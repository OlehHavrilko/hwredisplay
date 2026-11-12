using HardwareDisplay.Windows.Providers;

namespace HardwareDisplay.Tests;

public sealed class CpuBusyMathTests
{
    [Fact]
    public void Counts_half_busy_for_equal_busy_and_idle_deltas()
    {
        var previous = new CpuTimes(Idle: 1000, Kernel: 1500, User: 500);
        var current = new CpuTimes(Idle: 1500, Kernel: 2000, User: 1000);

        Assert.True(CpuBusyMath.TryCompute(previous, current, out var busy));
        Assert.Equal(50.0, busy, 6);
    }

    [Fact]
    public void Reports_full_load_when_idle_does_not_grow()
    {
        var previous = new CpuTimes(Idle: 1000, Kernel: 1500, User: 500);
        var current = new CpuTimes(Idle: 1000, Kernel: 1600, User: 600);

        Assert.True(CpuBusyMath.TryCompute(previous, current, out var busy));
        Assert.Equal(100.0, busy, 6);
    }

    [Fact]
    public void Reports_zero_for_idle_system()
    {
        var previous = new CpuTimes(Idle: 100, Kernel: 200, User: 100);
        var current = new CpuTimes(Idle: 200, Kernel: 300, User: 100);

        Assert.True(CpuBusyMath.TryCompute(previous, current, out var busy));
        Assert.Equal(0.0, busy, 6);
    }

    [Fact]
    public void Rejects_counter_reset()
    {
        var previous = new CpuTimes(Idle: 2000, Kernel: 3000, User: 1000);
        var current = new CpuTimes(Idle: 10, Kernel: 20, User: 5);

        Assert.False(CpuBusyMath.TryCompute(previous, current, out _));
    }

    [Fact]
    public void Rejects_stalled_counters()
    {
        var previous = new CpuTimes(Idle: 200, Kernel: 300, User: 100);
        var current = previous;

        Assert.False(CpuBusyMath.TryCompute(previous, current, out _));
    }
}
