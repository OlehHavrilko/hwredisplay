using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace HardwareDisplay.Host.Net;

public static class LanAddresses
{
    /// <summary>
    /// Адрес, который телефон вероятнее всего увидит: активный интерфейс с шлюзом,
    /// затем Wi-Fi/Ethernet, и только потом любой частный IPv4.
    /// </summary>
    public static IReadOnlyList<IPAddress> Candidates()
    {
        var result = new List<(int score, IPAddress address)>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            var properties = nic.GetIPProperties();
            var hasGateway = properties.GatewayAddresses.Any(g => g.Address != null && !g.Address.Equals(IPAddress.Any));

            foreach (var unicast in properties.UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork || IsLoopbackOrApipa(unicast.Address))
                {
                    continue;
                }

                var score = 0;
                if (hasGateway)
                {
                    score += 4;
                }

                score += nic.NetworkInterfaceType switch
                {
                    NetworkInterfaceType.Wireless80211 => 3,
                    NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet => 2,
                    _ => 0,
                };

                result.Add((score, unicast.Address));
            }
        }

        return result.OrderByDescending(r => r.score).Select(r => r.address).ToArray();
    }

    private static bool IsLoopbackOrApipa(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        var isApipa = bytes[0] == 169 && bytes[1] == 254;
        return IPAddress.IsLoopback(address) || isApipa;
    }
}
