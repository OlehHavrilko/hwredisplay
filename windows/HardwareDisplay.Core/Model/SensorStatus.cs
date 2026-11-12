namespace HardwareDisplay.Core.Model;

public static class SensorStatus
{
    public const string Ok = "ok";
    public const string Waiting = "waiting";
    public const string Unavailable = "unavailable";
    public const string Error = "error";
    public const string Stale = "stale";

    public static bool IsOk(string status) => status == Ok;
}

public static class SensorReason
{
    public const string Warmup = "warmup";
    public const string Unsupported = "unsupported";
    public const string NotApplicable = "not_applicable";
    public const string PermissionDenied = "permission_denied";
    public const string ProviderOffline = "provider_offline";
    public const string ReadFailed = "read_failed";
    public const string TimedOut = "timed_out";
    public const string Expired = "expired";
}

public static class SensorUnit
{
    public const string Percent = "percent";
    public const string Bytes = "bytes";
    public const string BytesPerSecond = "bytes_per_second";
    public const string Celsius = "celsius";
    public const string Hertz = "hertz";
    public const string Watt = "watt";
    public const string Volt = "volt";
    public const string Rpm = "rpm";
    public const string Count = "count";
}

public static class DeviceKind
{
    public const string Cpu = "cpu";
    public const string Gpu = "gpu";
    public const string Memory = "memory";
    public const string Storage = "storage";
    public const string Motherboard = "motherboard";
}
