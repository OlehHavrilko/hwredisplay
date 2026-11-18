using HardwareDisplay.Host.Desktop;

namespace HardwareDisplay.Tests;

public sealed class AutostartTests
{
    [Fact]
    public void Autostart_toggles_run_key_and_restores_previous_state()
    {
        var wasEnabled = Autostart.IsEnabled();

        try
        {
            Autostart.Set(true);
            Assert.True(Autostart.IsEnabled());

            Autostart.Set(false);
            Assert.False(Autostart.IsEnabled());
        }
        finally
        {
            Autostart.Set(wasEnabled);
        }

        Assert.Equal(wasEnabled, Autostart.IsEnabled());
    }
}
