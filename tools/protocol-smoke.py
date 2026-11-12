"""Проверка живого сервера HardwareDisplay без .NET.

Клиент повторяет то, что делает Android: TLS до логического имени ПК, TCP на
известный IPv4, сверка SHA-256 листа, затем HTTPS-сопряжение и WebSocket.

    python tools/protocol-smoke.py --ip 192.168.0.203 --port 18443 \
        --host pc-...hardwaredisplay.test --invite <32 hex> [--fingerprint <64 hex>]
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys

SUB_PROTOCOL = "hardwaredisplay.v1"


def read_http_response(sock, buffer):
    while b"\r\n\r\n" not in buffer[0]:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("сервер закрыл соединение до ответа")
        buffer[0] += chunk

    head, _, buffer[0] = buffer[0].partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    status = int(lines[0].split(" ")[1])
    headers = {}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        headers[key.strip().lower()] = value.strip()

    length = int(headers.get("content-length", "0"))
    while len(buffer[0]) < length:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buffer[0] += chunk

    body, buffer[0] = buffer[0][:length], buffer[0][length:]
    return status, headers, body


def recv_exact(sock, buffer, count):
    while len(buffer[0]) < count:
        chunk = sock.recv(65536)
        if not chunk:
            raise ConnectionError("соединение закрыто")
        buffer[0] += chunk
    payload, buffer[0] = buffer[0][:count], buffer[0][count:]
    return payload


def read_frame(sock, buffer):
    header = recv_exact(sock, buffer, 2)
    opcode = header[0] & 0x0F
    length = header[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, buffer, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, buffer, 8))[0]
    payload = recv_exact(sock, buffer, length) if length else b""
    return opcode, payload


def send_frame(sock, opcode, payload=b""):
    mask = os.urandom(4)
    header = bytes([0x80 | opcode])
    length = len(payload)
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", length)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(header + mask + masked)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", required=True)
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--host", required=True)
    parser.add_argument("--invite", required=True)
    parser.add_argument("--fingerprint", default=None, help="если не задан, отпечаток печатается для сверки глазами")
    parser.add_argument("--frames", type=int, default=3)
    args = parser.parse_args()

    token = "smoke-" + base64.urlsafe_b64encode(os.urandom(24)).decode().rstrip("=")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    authority = f"{args.host}:{args.port}"

    tls = context.wrap_socket(socket.create_connection((args.ip, args.port), timeout=15), server_hostname=args.host)
    buffer = [b""]

    pin = hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest()
    if args.fingerprint and pin != args.fingerprint:
        print(f"FAIL: отпечаток не совпал, сервер прислал {pin}")
        return 1
    print(f"TLS ok, pin={pin}")

    def https(method, path, body=None, bearer=None):
        payload = body.encode() if body else b""
        request = f"{method} {path} HTTP/1.1\r\nHost: {authority}\r\nConnection: keep-alive\r\n"
        if bearer:
            request += f"Authorization: Bearer {bearer}\r\n"
        if body:
            request += "Content-Type: application/json\r\n"
        request += f"Content-Length: {len(payload)}\r\n\r\n"
        tls.sendall(request.encode() + payload)
        return read_http_response(tls, buffer)

    status, _, _ = https("GET", "/v1/session")
    print(f"GET /v1/session без токена: {status} (ожидалось 401)")
    if status != 401:
        return 1

    status, headers, body = https(
        "POST",
        "/v1/pair",
        json.dumps({"v": 1, "invite": args.invite, "clientName": "Python smoke"}),
        token,
    )
    print(f"POST /v1/pair: {status} ({headers.get('cache-control')}) {body.decode('utf-8', 'replace').strip()}")
    if status != 201:
        return 1

    key = base64.b64encode(os.urandom(16)).decode()
    tls.sendall((
        "GET /v1/telemetry HTTP/1.1\r\n"
        f"Host: {authority}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Sec-WebSocket-Protocol: {SUB_PROTOCOL}\r\n"
        f"Authorization: Bearer {token}\r\n\r\n"
    ).encode())

    status, headers, _ = read_http_response(tls, buffer)
    protocol = headers.get("sec-websocket-protocol")
    print(f"GET /v1/telemetry: {status}, протокол {protocol}")
    if status != 101 or protocol != SUB_PROTOCOL:
        return 1

    seen = []
    while len(seen) < args.frames:
        opcode, payload = read_frame(tls, buffer)
        if opcode == 0x8:
            print("сокет закрыт сервером")
            break
        if opcode == 0x9:
            send_frame(tls, 0xA, payload)
            continue
        if opcode != 0x1:
            continue

        message = json.loads(payload.decode("utf-8"))
        seen.append(message["type"])

        if message["type"] == "hello":
            print(f"hello: hostId={message['hostId']} epoch={message['streamEpoch']} период={message['samplePeriodMs']} мс")
        elif message["type"] == "catalog":
            print(f"catalog: revision={message['revision']}, датчиков={len(message['sensors'])}")
            for sensor in message["sensors"]:
                print(f"  - {sensor['sensorId']}: {sensor['label']} [{sensor['unit']}] из {sensor['source']}")
        elif message["type"] == "snapshot":
            values = ", ".join(
                f"{s['sensorId']}={s.get('value')} ({s['status']})" for s in message["samples"]
            )
            print(f"snapshot #{message['seq']}: {values}")

    send_frame(tls, 0x8, struct.pack(">H", 1000))
    tls.close()
    print("OK: " + " → ".join(seen))
    return 0


if __name__ == "__main__":
    sys.exit(main())
