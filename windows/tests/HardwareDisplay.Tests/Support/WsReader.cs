using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace HardwareDisplay.Tests.Support;

/// <summary>
/// Клиент читает сокет непрерывно, как это делает OkHttp на телефоне:
/// кадры складываются в канал, закрытие фиксируется сразу.
/// </summary>
internal sealed class WsReader : IAsyncDisposable
{
    private readonly WebSocket _socket;
    private readonly Channel<(string? Text, WebSocketCloseStatus? Close)> _messages = Channel.CreateUnbounded<(string?, WebSocketCloseStatus?)>();
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(20));
    private readonly Task _loop;

    public WsReader(WebSocket socket)
    {
        _socket = socket;
        _loop = Task.Run(ReadLoopAsync);
    }

    public async Task<JsonDocument> ReadJsonAsync(int timeoutMs = 5000)
    {
        var text = await ReadTextAsync(timeoutMs);
        return JsonDocument.Parse(text);
    }

    public async Task<string> ReadTextAsync(int timeoutMs = 5000)
    {
        using var cts = new CancellationTokenSource(timeoutMs);

        while (true)
        {
            var (text, close) = await _messages.Reader.ReadAsync(cts.Token);
            if (text is not null)
            {
                return text;
            }

            throw new InvalidOperationException($"Сокет закрыт: {close}");
        }
    }

    public async Task<WebSocketCloseStatus?> WaitCloseAsync(int timeoutMs = 10000)
    {
        using var cts = new CancellationTokenSource(timeoutMs);

        await foreach (var (text, close) in _messages.Reader.ReadAllAsync(cts.Token))
        {
            if (text is null)
            {
                return close;
            }
        }

        return null;
    }

    private async Task ReadLoopAsync()
    {
        var buffer = new byte[64 * 1024];
        using var payload = new MemoryStream();

        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var result = await _socket.ReceiveAsync(buffer, _cts.Token);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    _messages.Writer.TryWrite((null, result.CloseStatus));
                    _messages.Writer.TryComplete();
                    return;
                }

                payload.Write(buffer, 0, result.Count);
                if (result.EndOfMessage)
                {
                    _messages.Writer.TryWrite((Encoding.UTF8.GetString(payload.ToArray()), null));
                    payload.SetLength(0);
                }
            }
        }
        catch (Exception)
        {
            _messages.Writer.TryWrite((null, null));
            _messages.Writer.TryComplete();
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try
        {
            await _loop;
        }
        catch (Exception)
        {
        }

        _cts.Dispose();
    }
}
