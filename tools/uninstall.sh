#!/bin/sh
# Give Home back to another launcher and remove Spotlight.
#
#   TV_HOST=192.168.1.50 tools/uninstall.sh com.google.android.tvlauncher/.MainActivity
#
# The argument is the launcher to hand Home to. To see what's installed:
#   .venv/bin/python tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
# If you disabled the stock launcher, enable it first: tools/tv.py 'pm enable <package>'.
set -eu
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
TO=${1:?name the launcher to hand Home to, e.g. com.google.android.tvlauncher/.MainActivity}
PY=${PYTHON:-.venv/bin/python}
PKG=io.github.iyiolajay.spotlight

tv() { "$PY" -I tools/tv.py "$@"; }

tv "pm enable ${TO%%/*}; cmd package set-home-activity $TO; cmd role add-role-holder android.app.role.HOME ${TO%%/*}" >/dev/null
tv "pm uninstall $PKG; input keyevent KEYCODE_HOME"
sleep 3
tv "dumpsys activity activities | grep -m1 mFocusedApp"
