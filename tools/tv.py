#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on the adb-shell package.

Adapted from CleanYourTV's tools/tv.py (https://github.com/Gathin23/cleanyourtv, MIT,
Copyright (c) 2026 Gathin); see LICENSE.

  tools/tv.py 'getprop ro.product.model'      run a shell command
  tools/tv.py --screenshot shots/01.png       capture on the TV and pull the PNG
  tools/tv.py --push local.apk /data/local/tmp/x.apk
  tools/tv.py --pull /sdcard/file local
  tools/tv.py --key DPAD_DOWN DPAD_DOWN ENTER inject remote key presses

Host and port come from TV_HOST (required, the TV's IP address) and TV_PORT (default 5555).
The key pair lives in ~/.android/adbkey, the same one the stock adb uses.
"""
import base64
import hashlib
import os
import sys
import time

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_HOST") or sys.exit("Set TV_HOST to the TV's IP address, e.g. TV_HOST=192.168.1.50")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.expanduser("~/.android/adbkey")


def connect(auth_timeout=60):
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=30)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=auth_timeout)
    return dev


def shell(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout, decode=True)


def md5_remote(dev, path):
    out = shell(dev, f"md5sum '{path}' 2>/dev/null").strip()
    return out.split()[0] if out else ""


def push(dev, local, remote):
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    # The sync protocol push hangs on this TV (and leaves the connection unusable), so send
    # base64 through the shell in chunks and decode on the TV
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    shell(dev, f"rm -f '{tmp}' '{remote}'")
    step = 60000
    for i in range(0, len(b64), step):
        shell(dev, f"echo -n '{b64[i:i + step]}' >> '{tmp}'")
    shell(dev, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
    got = md5_remote(dev, remote)
    if got != want:
        sys.exit(f"push verify failed: {got} != {want}")


def pull(dev, remote, local):
    os.makedirs(os.path.dirname(os.path.abspath(local)), exist_ok=True)
    dev.pull(remote, local, read_timeout_s=60)


def screenshot(dev, local):
    remote = "/sdcard/_shot.png"
    shell(dev, f"screencap -p {remote}")
    pull(dev, remote, local)
    shell(dev, f"rm -f {remote}")
    if os.path.getsize(local) == 0:
        print("screenshot is 0 bytes: the TV blanks captures of Live TV/HDMI "
              "(check dumpsys activity activities | grep topResumedActivity)", file=sys.stderr)
        sys.exit(1)
    print(local)


def main(argv):
    if not argv:
        sys.exit(__doc__)
    dev = connect(auth_timeout=float(os.environ.get("TV_AUTH_TIMEOUT", "60")))
    try:
        if argv[0] == "--screenshot":
            screenshot(dev, argv[1])
        elif argv[0] == "--push":
            push(dev, argv[1], argv[2])
        elif argv[0] == "--pull":
            pull(dev, argv[1], argv[2])
        elif argv[0] == "--key":
            for k in argv[1:]:
                if k.startswith("sleep"):
                    time.sleep(float(k[5:] or 1))
                    continue
                code = k if k.isdigit() or k.startswith("KEYCODE_") else "KEYCODE_" + k
                shell(dev, f"input keyevent {code}")
                time.sleep(0.35)
        else:
            sys.stdout.write(shell(dev, " ".join(argv)))
    finally:
        dev.close()


if __name__ == "__main__":
    main(sys.argv[1:])
