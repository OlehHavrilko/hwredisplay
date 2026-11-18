using System.Runtime.InteropServices;
using HardwareDisplay.Windows.Interop;

namespace HardwareDisplay.Windows.Providers;

public sealed record GpuAdapter(
    string DeviceId,
    string Label,
    ulong Luid,
    long DedicatedVideoMemory,
    uint VendorId,
    uint DeviceNumber);

/// <summary>
/// Инвентарь адаптеров через DXGI. LUID связывает адаптер со счётчиками PDH текущего сеанса,
/// а идентификатор датчиков выводится из вендора и модели, чтобы карточки переживали перезапуск.
/// </summary>
public static class GpuInventory
{
    public static IReadOnlyList<GpuAdapter> Enumerate()
    {
        var adapters = new List<GpuAdapter>();
        var seenByModel = new Dictionary<string, int>(StringComparer.Ordinal);

        IntPtr factoryPointer = IntPtr.Zero;
        IDxgiFactory1? factory = null;
        try
        {
            var iid = Dxgi.Factory1;
            if (Dxgi.CreateDXGIFactory1(ref iid, out factoryPointer) != 0 || factoryPointer == IntPtr.Zero)
            {
                return adapters;
            }

            factory = (IDxgiFactory1)Marshal.GetObjectForIUnknown(factoryPointer);

            for (uint index = 0; index < 16; index++)
            {
                if (factory.EnumAdapters1(index, out var adapterPointer) != 0 || adapterPointer == IntPtr.Zero)
                {
                    break;
                }

                try
                {
                    var adapter = (IDxgiAdapter1)Marshal.GetObjectForIUnknown(adapterPointer);
                    if (adapter.GetDesc1(out var desc) != 0)
                    {
                        continue;
                    }

                    if ((desc.Flags & Dxgi.AdapterFlagSoftware) != 0)
                    {
                        continue;
                    }

                    var modelKey = $"{desc.VendorId:X4}{desc.DeviceId:X4}";
                    seenByModel.TryGetValue(modelKey, out var ordinal);
                    seenByModel[modelKey] = ordinal + 1;

                    adapters.Add(new GpuAdapter(
                        DeviceId: $"gpu-{modelKey.ToLowerInvariant()}-{ordinal + 1}",
                        Label: string.IsNullOrWhiteSpace(desc.Description) ? $"GPU {index}" : desc.Description.Trim(),
                        Luid: desc.AdapterLuid.Value,
                        DedicatedVideoMemory: (long)Math.Min((ulong)desc.DedicatedVideoMemory, (ulong)long.MaxValue),
                        VendorId: desc.VendorId,
                        DeviceNumber: index));
                }
                finally
                {
                    Marshal.Release(adapterPointer);
                }
            }
        }
        catch (Exception)
        {
            return adapters;
        }
        finally
        {
            if (factory is not null)
            {
                Marshal.ReleaseComObject(factory);
            }
            else if (factoryPointer != IntPtr.Zero)
            {
                Marshal.Release(factoryPointer);
            }
        }

        return adapters;
    }
}
