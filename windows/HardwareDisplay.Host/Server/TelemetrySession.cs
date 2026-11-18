using System.Net.WebSockets;
using System.Text.Json;
using HardwareDisplay.Core.Collection;
using HardwareDisplay.Core.Protocol;

namespace HardwareDisplay.Host.Server;

internal sealed class SessionHandle : IDisposable
{
    public CancellationTokenSource Cancellation { get; } = new();

    public WebSocketCloseStatus CloseStatus { get; set; } = WebSocketCloseStatus.NormalClosure;

    public string CloseReason { get; set; } = "server_stop";

    public void Dispose() => Cancellation.Dispose();
}

/// <summary>
/// У сокета один отправитель и буфер одного свежего снимка: переполнение пропускает
/// устаревшую телеметрию и не останавливает сборщик.
/// </summary>
internal static class TelemetrySession
{
    public static async Task RunAsync(
        WebSocket socket,
        TelemetryCollector collector,
        string hostId,
        SessionHandle handle,
        TimeSpan sendTimeout)
    {
        var token = handle.Cancellation.Token;
        var latest = new LatestFrame(collector.Latest);
        void OnFrame(TelemetryFrame frame) => latest.Set(frame);

        collector.FramePublished += OnFrame;
        var receiving = Task.CompletedTask;
        try
        {
            await SendAsync(
                    socket,
                    JsonSerializer.SerializeToUtf8Bytes(
                        HelloMessage.Create(hostId, collector.StreamEpoch, collector.SamplePeriodMs),
                        ProtocolJson.Options),
                    sendTimeout,
                    token).ConfigureAwait(false);

            receiving = ReceiveAsync(socket);
            var sentRevision = -1L;

            while (!token.IsCancellationRequested)
            {
                var frame = await latest.WaitAsync(token).ConfigureAwait(false);
                if (frame is null)
                {
                    break;
                }

                if (frame.Catalog.Revision != sentRevision)
                {
                    await SendAsync(socket, frame.CatalogPayload, sendTimeout, token).ConfigureAwait(false);
                    sentRevision = frame.Catalog.Revision;
                }

                await SendAsync(socket, frame.SnapshotPayload, sendTimeout, token).ConfigureAwait(false);

                if (receiving.IsCompleted)
                {
                    break;
                }
            }

            await CloseAsync(socket, handle.CloseStatus, handle.CloseReason).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            await CloseAsync(socket, handle.CloseStatus, handle.CloseReason).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            await CloseAsync(socket, WebSocketCloseStatus.PolicyViolation, "slow_client").ConfigureAwait(false);
        }
        catch (WebSocketException)
        {
        }
        finally
        {
            collector.FramePublished -= OnFrame;
            await Task.WhenAny(receiving, Task.Delay(250)).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Кадр уже сериализован один раз на замер, поэтому отправка не тратит CPU пропорционально
    /// числу телефонов и не создаёт копию JSON на каждое соединение.
    /// </summary>
    private static async Task SendAsync(WebSocket socket, byte[] payload, TimeSpan timeout, CancellationToken token)
    {
        await socket.SendAsync(payload, WebSocketMessageType.Text, true, token).WaitAsync(timeout, token).ConfigureAwait(false);
    }

    /// <summary>
    /// Чтение идёт без токена отмены: в Kestrel отмена незавершённого чтения обрывает
    /// транспорт, и подтверждение закрытия до клиента уже не доходит.
    /// </summary>
    private static async Task ReceiveAsync(WebSocket socket)
    {
        var buffer = new byte[1024];
        try
        {
            while (true)
            {
                var result = await socket.ReceiveAsync(buffer, CancellationToken.None).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    return;
                }
            }
        }
        catch (Exception)
        {
        }
    }

    private static async Task CloseAsync(WebSocket socket, WebSocketCloseStatus status, string reason)
    {
        if (socket.State is not (WebSocketState.Open or WebSocketState.CloseReceived))
        {
            return;
        }

        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await socket.CloseAsync(status, reason, timeout.Token).ConfigureAwait(false);
        }
        catch (Exception)
        {
        }
    }

    private sealed class LatestFrame
    {
        private readonly SemaphoreSlim _signal = new(0, 1);
        private TelemetryFrame? _frame;

        public LatestFrame(TelemetryFrame? initial)
        {
            _frame = initial;
            if (initial is not null)
            {
                Release();
            }
        }

        public void Set(TelemetryFrame frame)
        {
            Volatile.Write(ref _frame, frame);
            Release();
        }

        public async Task<TelemetryFrame?> WaitAsync(CancellationToken token)
        {
            await _signal.WaitAsync(token).ConfigureAwait(false);
            return Volatile.Read(ref _frame);
        }

        private void Release()
        {
            if (_signal.CurrentCount != 0)
            {
                return;
            }

            try
            {
                _signal.Release();
            }
            catch (SemaphoreFullException)
            {
            }
        }
    }
}
