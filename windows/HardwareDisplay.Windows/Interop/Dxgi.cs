using System.Runtime.InteropServices;

namespace HardwareDisplay.Windows.Interop;

[StructLayout(LayoutKind.Sequential)]
internal struct Luid
{
    public uint LowPart;
    public int HighPart;

    public readonly ulong Value => ((ulong)(uint)HighPart << 32) | LowPart;
}

[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
internal struct DxgiAdapterDesc1
{
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
    public string Description;

    public uint VendorId;
    public uint DeviceId;
    public uint SubSysId;
    public uint Revision;
    public nuint DedicatedVideoMemory;
    public nuint DedicatedSystemMemory;
    public nuint SharedSystemMemory;
    public Luid AdapterLuid;
    public uint Flags;
}

/// <summary>
/// DXGI даёт инвентарь адаптеров и настоящий объём выделенной видеопамяти:
/// WMI обрезает AdapterRAM до 4 ГиБ, а PDH сообщает только занятые объёмы по LUID.
/// </summary>
internal static class Dxgi
{
    public const uint AdapterFlagSoftware = 2;
    public const int ErrorNotFound = unchecked((int)0x887A0002);

    [DllImport("dxgi.dll", ExactSpelling = true)]
    public static extern int CreateDXGIFactory1(ref Guid riid, out IntPtr factory);

    public static readonly Guid Factory1 = new("770aae78-f26f-4dba-a829-253c83d1b387");
}

[ComImport]
[Guid("770aae78-f26f-4dba-a829-253c83d1b387")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IDxgiFactory1
{
    [PreserveSig] int SetPrivateData(ref Guid name, uint size, IntPtr data);
    [PreserveSig] int SetPrivateDataInterface(ref Guid name, IntPtr unknown);
    [PreserveSig] int GetPrivateData(ref Guid name, ref uint size, IntPtr data);
    [PreserveSig] int GetParent(ref Guid riid, out IntPtr parent);
    [PreserveSig] int EnumAdapters(uint index, out IntPtr adapter);
    [PreserveSig] int MakeWindowAssociation(IntPtr window, uint flags);
    [PreserveSig] int GetWindowAssociation(out IntPtr window);
    [PreserveSig] int CreateSwapChain(IntPtr device, IntPtr desc, out IntPtr swapChain);
    [PreserveSig] int CreateSoftwareAdapter(IntPtr module, out IntPtr adapter);
    [PreserveSig] int EnumAdapters1(uint index, out IntPtr adapter);
    [PreserveSig] int IsCurrent();
}

[ComImport]
[Guid("29038f61-3839-4626-91fd-086879011a05")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IDxgiAdapter1
{
    [PreserveSig] int SetPrivateData(ref Guid name, uint size, IntPtr data);
    [PreserveSig] int SetPrivateDataInterface(ref Guid name, IntPtr unknown);
    [PreserveSig] int GetPrivateData(ref Guid name, ref uint size, IntPtr data);
    [PreserveSig] int GetParent(ref Guid riid, out IntPtr parent);
    [PreserveSig] int EnumOutputs(uint index, out IntPtr output);
    [PreserveSig] int GetDesc(IntPtr desc);
    [PreserveSig] int CheckInterfaceSupport(ref Guid riid, out long umdVersion);
    [PreserveSig] int GetDesc1(out DxgiAdapterDesc1 desc);
}
