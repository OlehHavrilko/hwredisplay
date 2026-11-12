using System.Runtime.InteropServices;

namespace HardwareDisplay.Host.Identity;

internal static partial class Dpapi
{
    private const int CryptProtectUiForbidden = 0x1;

    public static byte[] Protect(byte[] plain)
    {
        var input = new DataBlob(plain);
        try
        {
            if (!CryptProtectData(ref input, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, out var output))
            {
                throw new InvalidOperationException("DPAPI не зашифровал данные: " + Marshal.GetLastWin32Error());
            }

            try
            {
                return Copy(output);
            }
            finally
            {
                LocalFree(output.Data);
            }
        }
        finally
        {
            input.Free();
        }
    }

    public static byte[] Unprotect(byte[] cipher)
    {
        var input = new DataBlob(cipher);
        try
        {
            if (!CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, out var output))
            {
                throw new InvalidOperationException("DPAPI не расшифровал данные: " + Marshal.GetLastWin32Error());
            }

            try
            {
                return Copy(output);
            }
            finally
            {
                LocalFree(output.Data);
            }
        }
        finally
        {
            input.Free();
        }
    }

    private static byte[] Copy(DataBlob blob)
    {
        var bytes = new byte[blob.Size];
        Marshal.Copy(blob.Data, bytes, 0, blob.Size);
        return bytes;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int Size;
        public IntPtr Data;

        public DataBlob(byte[] bytes)
        {
            Size = bytes.Length;
            Data = Marshal.AllocHGlobal(bytes.Length);
            Marshal.Copy(bytes, 0, Data, bytes.Length);
        }

        public readonly void Free()
        {
            if (Data != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(Data);
            }
        }
    }

    [LibraryImport("crypt32.dll", EntryPoint = "CryptProtectData", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CryptProtectData(ref DataBlob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob output);

    [LibraryImport("crypt32.dll", EntryPoint = "CryptUnprotectData", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CryptUnprotectData(ref DataBlob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob output);

    [LibraryImport("kernel32.dll")]
    private static partial IntPtr LocalFree(IntPtr memory);
}
