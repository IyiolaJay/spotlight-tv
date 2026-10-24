#!/usr/bin/env python3
"""Check that the TV's network ADB port is really closed.

Adapted from CleanYourTV's tools/adb-closed.py (https://github.com/Gathin23/cleanyourtv, MIT,
Copyright (c) 2026 Gathin); see CLEANYOURTV-LICENSE.txt.

  tools/adb-closed.py 192.168.1.50 [port]

Only "Connection refused" proves the port is closed: the TV answered and nothing listens. A
timeout or "No route to host" proves nothing (the TV may be off, asleep, or macOS Local Network
privacy may be blocking this program), so those exit 2, not 0.
"""
import errno
import socket
import sys


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    host, port = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 5555
    s = socket.socket()
    s.settimeout(5)
    try:
        s.connect((host, port))
    except ConnectionRefusedError:
        print(f"CLOSED: {host}:{port} refused the connection. Network ADB is off.")
        return 0
    except OSError as e:
        reason = errno.errorcode.get(e.errno, "") if e.errno else type(e).__name__
        print(f"UNKNOWN: {host}:{port} did not answer ({reason}: {e}). Is the TV on and on Wi-Fi?")
        return 2
    finally:
        s.close()
    print(f"OPEN: {host}:{port} accepted a connection. Network ADB is still on.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
