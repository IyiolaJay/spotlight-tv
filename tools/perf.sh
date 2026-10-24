#!/bin/sh
# Frame timing and memory for a home screen, under the same remote presses every time, so two
# launchers can be compared fairly.
#
# Usage: tools/perf.sh io.github.iyiolajay.spotlight/.HomeActivity
#        tools/perf.sh com.gathin.tvhome/.MainActivity
set -eu
cd "$(dirname "$0")/.."
. .venv/bin/activate
CMP=$1
PKG=${CMP%%/*}
tv() { python3 tools/tv.py "$@"; }

tv "input keyevent KEYCODE_WAKEUP; am start -n $CMP >/dev/null 2>&1; sleep 3; dumpsys gfxinfo $PKG reset >/dev/null"
# Down through the list and back, across to the actions and the top row, with real-remote pauses
tv --key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN \
         DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP \
         DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_LEFT DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_LEFT DPAD_DOWN sleep1
echo "== $PKG"
tv "dumpsys gfxinfo $PKG" | grep -E "Total frames rendered|Janky frames|50th|90th|95th|99th|Number Slow|Number Frame deadline" | head -10
tv "dumpsys meminfo $PKG" | grep -E "TOTAL PSS|TOTAL:|Graphics:|Native Heap:|Java Heap:" | head -6
