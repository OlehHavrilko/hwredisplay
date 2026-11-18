using System.Runtime.InteropServices;

namespace HardwareDisplay.Windows.Interop;

/// <summary>
/// PDH с английскими именами счётчиков: локализованные имена на русской Windows
/// не совпадают со строками в документации, поэтому пути всегда английские.
/// </summary>
internal static class Pdh
{
    internal const uint ErrorSuccess = 0;
    internal const uint StatusValidData = 0;
    internal const uint StatusNewData = 1;
    internal const uint MoreData = 0x800007D2;
    internal const uint NoData = 0x800007D5;

    internal const uint FormatDouble = 0x00000200;

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhOpenQueryW(string? dataSource, IntPtr userData, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhCloseQuery(IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhAddEnglishCounterW(IntPtr query, string path, IntPtr userData, out IntPtr counter);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhRemoveCounter(IntPtr counter);

    [DllImport("pdh.dll")]
    internal static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhExpandWildCardPathW(
        string? dataSource,
        string wildCardPath,
        IntPtr buffer,
        ref uint bufferSize,
        uint flags);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhGetFormattedCounterValue(IntPtr counter, uint format, out uint type, out PdhFormatCounterValue value);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    internal static extern uint PdhGetFormattedCounterArrayW(
        IntPtr counter,
        uint format,
        ref uint bufferSize,
        out uint itemCount,
        IntPtr buffer);

    [StructLayout(LayoutKind.Explicit)]
    internal struct PdhFormatCounterValue
    {
        [FieldOffset(0)] internal uint Status;

        [FieldOffset(8)] internal double DoubleValue;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct PdhFormatCounterValueItem
    {
        internal IntPtr Name;
        internal PdhFormatCounterValue Value;
    }
}
