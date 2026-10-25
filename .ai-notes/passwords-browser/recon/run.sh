#!/bin/bash
# runs as user u inside the container
export XDG_RUNTIME_DIR=/run/user/$(id -u) DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/$(id -u)/bus
mkdir -p ~/.mozilla/native-messaging-hosts ~/ext ~/ffprof
cp /probe/ext/* ~/ext/
cp /probe/host.py ~/host.py; chmod +x ~/host.py
cat > ~/.mozilla/native-messaging-hosts/cx.m42.unitool.probe.json <<J
{"name":"cx.m42.unitool.probe","description":"probe","path":"$HOME/host.py","type":"stdio","allowed_extensions":["probe@unitool.m42.cx"]}
J
[ "$PRESET" = 1 ] && flatpak permission-set webextensions cx.m42.unitool.probe snap.firefox yes
flatpak permission-show snap.firefox 2>&1
rm -f ~/host.log
Xvfb :55 -screen 0 1280x800x24 >/dev/null 2>&1 & XV=$!
export DISPLAY=:55
sleep 1; systemctl --user import-environment DISPLAY; systemctl --user set-environment G_MESSAGES_DEBUG=all; systemctl --user restart xdg-desktop-portal-gtk xdg-desktop-portal; sleep 1; systemctl --user is-active xdg-desktop-portal xdg-desktop-portal-gtk
PROBE_LOG=$HOME/host.log timeout ${T:-60} node /opt/tools/node_modules/.bin/web-ext run --source-dir ~/ext --firefox /snap/bin/firefox \
  --firefox-profile ~/ffprof --profile-create-if-missing --keep-profile-changes --no-input --no-reload --verbose --pref devtools.console.stdout.chrome=true --pref browser.dom.window.dump.enabled=true --pref devtools.console.stdout.content=true $EXTRA > ~/webext.out 2>&1
kill $XV
echo "--- host.log"; cat ~/host.log 2>&1
echo "--- portal"; journalctl --user -n 30 --no-pager 2>/dev/null | grep -i -E 'portal|webext' | tail -10
