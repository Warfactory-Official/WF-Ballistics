#!/usr/bin/env python3
"""Minimal Source RCON client. Reads commands on stdin, one per line, prints responses."""
import socket
import struct
import sys
import time

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "bench"


class Rcon:
    def __init__(self, host=HOST, port=PORT, password=PASSWORD, timeout=60.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.rid = 0
        if self._send(3, password) is None:
            raise SystemExit("rcon auth failed")

    def _pack(self, rid, kind, body):
        payload = struct.pack("<ii", rid, kind) + body.encode("utf8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _readn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon closed")
            buf += chunk
        return buf

    def _send(self, kind, body):
        self.rid += 1
        rid = self.rid
        self.sock.sendall(self._pack(rid, kind, body))
        size = struct.unpack("<i", self._readn(4))[0]
        payload = self._readn(size)
        got, _ = struct.unpack("<ii", payload[:8])
        if got == -1:
            return None
        return payload[8:-2].decode("utf8", "replace")

    def cmd(self, command):
        return self._send(2, command)


def main():
    deadline = time.time() + 240
    while True:
        try:
            r = Rcon()
            break
        except (OSError, ConnectionError):
            if time.time() > deadline:
                raise SystemExit("rcon never came up")
            time.sleep(2)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line.startswith("#sleep "):
            time.sleep(float(line.split()[1]))
            continue
        if line.startswith("#echo "):
            print(line[6:], flush=True)
            continue
        out = r.cmd(line)
        if out:
            print(out.rstrip(), flush=True)


if __name__ == "__main__":
    main()
