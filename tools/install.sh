#!/bin/sh
# Build Spotlight, install it on the TV, set it up and make it Home.
#
#   TV_HOST=192.168.1.50 NAME=Ada tools/install.sh
#
# Settings (environment variables, all optional):
#   NAME=Ada              the greeting ("Good evening, Ada") and the faint signature; NAME=none for neither
#   FAVOURITES=pkg,pkg    the apps at the top of the list, in order (default: config.xml)
#   PHOTO=~/beach.jpg     your own background instead of the painted beach (cropped to 16:9, dark side baked in)
#   BOOT_TO_HOME=on       come straight back when the firmware opens Live TV at power-on (UK Freeview Play sets)
# Any of them can be changed later without a rebuild: see README, "Change settings later".
set -eu
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
PY=${PYTHON:-.venv/bin/python}
PKG=io.github.iyiolajay.spotlight

tv() { "$PY" -I tools/tv.py "$@"; }

if [ -n "${PHOTO:-}" ]; then
  "$PY" -I tools/bake-landscape.py "$PHOTO"
fi
[ -f launcher/local.properties ] || echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > launcher/local.properties
(cd launcher && ./gradlew -q assembleRelease)

# Plain `adb install` can wait forever for an on-screen confirmation; pm install over the shell doesn't
tv --push launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/spotlight.apk
tv "pm install -r /data/local/tmp/spotlight.apk; rm -f /data/local/tmp/spotlight.apk"

# Granted here, never asked for on the TV: usage access ("Last opened", and spotting Live TV at
# power-on), the background-start exemption that lets Home come back in front of it (Spotlight never
# draws over other apps), and location, without which Android hides the Wi-Fi name from apps
tv "appops set $PKG GET_USAGE_STATS allow; appops set $PKG SYSTEM_ALERT_WINDOW allow; \
    pm grant $PKG android.permission.ACCESS_FINE_LOCATION; pm grant $PKG android.permission.ACCESS_COARSE_LOCATION"

# Settings, through Spotlight's own receiver. -f 32 reaches the app before it has ever run.
ARGS=""
[ -n "${NAME:-}" ] && ARGS="$ARGS --es name '$(printf %s "$NAME" | sed "s/'/'\\\\''/g")'"
[ -n "${FAVOURITES:-}" ] && ARGS="$ARGS --es favourites $FAVOURITES"
[ -n "${BOOT_TO_HOME:-}" ] && ARGS="$ARGS --es boot_to_home $BOOT_TO_HOME"
tv "am broadcast -f 32 -n $PKG/.ConfigReceiver -a $PKG.CONFIG $ARGS" >/dev/null

tv "cmd package set-home-activity $PKG/.HomeActivity; cmd role add-role-holder android.app.role.HOME $PKG; \
    input keyevent KEYCODE_HOME" >/dev/null
sleep 3
FOCUSED=$(tv "dumpsys activity activities | grep -m1 mFocusedApp")
case "$FOCUSED" in
  *"$PKG"*) echo "Spotlight is Home." ;;
  *) echo "Installed, but Home is still something else:" >&2
     echo "  $FOCUSED" >&2
     echo "The stock launcher probably outranks it; see README, \"If Home doesn't switch\"." >&2
     exit 1 ;;
esac
