using System.Runtime.InteropServices;
using HardwareDisplay.Windows.Interop;

namespace HardwareDisplay.Windows.Providers;

internal readonly record struct PdhSample(string? Instance, double Value);

/// <summary>
/// Один запрос PDH на группу счётчиков. Счётчики открываются английскими путями,
/// значения читаются массивом, потому что наборы экземпляров меняются на ходу.
/// </summary>
internal sealed class PdhQuery : IDisposable
{
    private readonly List<PdhCounter> _counters = [];
    private IntPtr _query;
    private int _collects;

    public PdhQuery()
    {
        if (PdhRound(Pdh.PdhOpenQueryW(null, IntPtr.Zero, out var query)) != Pdh.ErrorSuccess)
        {
            _query = IntPtr.Zero;
            return;
        }

        _query = query;
    }

    public bool IsOpen => _query != IntPtr.Zero;

    public PdhCounter? TryAdd(string path, bool wildcard)
    {
        if (!IsOpen)
        {
            return null;
        }

        if (PdhRound(Pdh.PdhAddEnglishCounterW(_query, path, IntPtr.Zero, out var handle)) != Pdh.ErrorSuccess)
        {
            return null;
        }

        var counter = new PdhCounter(handle, wildcard);
        _counters.Add(counter);
        return counter;
    }

    /// <summary>
    /// Первый сбор нужен, чтобы дифференциальные счётчики получили базу:
    /// до второго сбора их значения недостоверны.
    /// </summary>
    public bool Collect()
    {
        if (!IsOpen)
        {
            return false;
        }

        if (PdhRound(Pdh.PdhCollectQueryData(_query)) != Pdh.ErrorSuccess)
        {
            return false;
        }

        _collects++;
        return true;
    }

    /// <summary>Дифференциальные счётчики дают достоверное значение только со второго сбора.</summary>
    public bool IsPrimed => _collects >= 2;

    public void Dispose()
    {
        foreach (var counter in _counters)
        {
            Pdh.PdhRemoveCounter(counter.Handle);
        }

        _counters.Clear();

        if (_query != IntPtr.Zero)
        {
            Pdh.PdhCloseQuery(_query);
            _query = IntPtr.Zero;
        }
    }

    private static uint PdhRound(uint status) => status;

}

internal sealed class PdhCounter(IntPtr handle, bool wildcard)
{
    public IntPtr Handle { get; } = handle;

    public IReadOnlyList<PdhSample> Read()
    {
        return wildcard ? ReadArray(skipInvalid: true) : ReadSingle();
    }

    /// <summary>
    /// Имена экземпляров известны сразу после первого сбора, даже когда значения ещё
    /// недостоверны: состав дисков нужен для каталога до второго замера.
    /// </summary>
    public IReadOnlyList<string> ReadInstanceNames() =>
        wildcard ? ReadArray(skipInvalid: false).Select(s => s.Instance ?? string.Empty).Where(n => n.Length > 0).ToArray() : [];

    private IReadOnlyList<PdhSample> ReadSingle()
    {
        var status = Pdh.PdhGetFormattedCounterValue(Handle, Pdh.FormatDouble, out _, out var value);
        if (status != Pdh.ErrorSuccess || (value.Status != Pdh.StatusValidData && value.Status != Pdh.StatusNewData))
        {
            return [];
        }

        return [new PdhSample(null, value.DoubleValue)];
    }

    private IReadOnlyList<PdhSample> ReadArray(bool skipInvalid)
    {
        uint size = 0;
        uint count = 0;
        var probe = Pdh.PdhGetFormattedCounterArrayW(Handle, Pdh.FormatDouble, ref size, out count, IntPtr.Zero);

        if (probe != Pdh.MoreData || size == 0)
        {
            return [];
        }

        var buffer = Marshal.AllocHGlobal((int)size);
        try
        {
            if (Pdh.PdhGetFormattedCounterArrayW(Handle, Pdh.FormatDouble, ref size, out count, buffer) != Pdh.ErrorSuccess)
            {
                return [];
            }

            var itemSize = IntPtr.Size + 16;
            var samples = new List<PdhSample>((int)count);

            for (var index = 0; index < count; index++)
            {
                var item = buffer + (index * itemSize);
                var namePointer = Marshal.ReadIntPtr(item);
                var status = (uint)Marshal.ReadInt32(item, IntPtr.Size);
                var value = BitConverter.Int64BitsToDouble(Marshal.ReadInt64(item, IntPtr.Size + 8));

                if (skipInvalid && status is not (Pdh.StatusValidData or Pdh.StatusNewData))
                {
                    continue;
                }

                var name = namePointer == IntPtr.Zero ? null : Marshal.PtrToStringUni(namePointer);
                samples.Add(new PdhSample(name, value));
            }

            return samples;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }
}
