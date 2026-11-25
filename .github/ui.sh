#!/usr/bin/env bash
# Walks through the app on an emulator (tabs, settings, themes, a download, the browser) and
# fails with the stack trace if it crashes. Screenshots go to ./shots.
PKG=bot.paradox.downloader.app
mkdir -p shots
n=0
shot() { n=$((n+1)); adb exec-out screencap -p > "shots/$(printf %02d $n)-$1.png" 2>/dev/null; }
# Only the app's own process counts: a Java crash names it after "Process:", a native one between
# ">>> <<<". On the x86 emulator yt-dlp's ARM helper programs can't start, and the linker's message
# mentions the app's folder without the app having crashed.
CRASHED="Process: $PKG|>>> $PKG"
check() {
  if adb logcat -d -b crash | grep -qE "$CRASHED"; then
    echo "::error::app crashed after: $1"
    adb logcat -d -b crash | head -80
    shot "crash"
    exit 1
  fi
}
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml u.xml >/dev/null 2>&1; }
# find <mode> <text>: mode exact|contains|id ; prints "x y" of the first match
find_xy() {
  dump
  python3 - "$1" "$2" <<'PY'
import sys,re
import xml.etree.ElementTree as ET
mode,q=sys.argv[1],sys.argv[2].lower()
try:
    root=ET.parse('u.xml')
except Exception:
    sys.exit(0)
for e in root.iter('node'):
    t=(e.get('text') or '').strip().lower(); d=(e.get('content-desc') or '').strip().lower(); r=(e.get('resource-id') or '').lower()
    ok=(mode=='exact' and q in (t,d)) or (mode=='contains' and (q in t or q in d)) or (mode=='id' and r.endswith('/'+q))
    if ok:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',e.get('bounds')))
        print((x1+x2)//2,(y1+y2)//2); break
PY
}
# Two one-time prompts can cover the screen at any moment: the channel invite on launch ("Later")
# and, after the first download, the background-running question ("Not now").
dismiss_prompts() {
  local p xy
  for p in "Later" "Not now"; do
    xy=$(find_xy exact "$p")
    if [ -n "$xy" ]; then echo "(dismissed a prompt: $p)"; adb shell input tap $xy; sleep 2; fi
  done
}
tap() { # tap <mode> <text>
  local xy; xy=$(find_xy "$1" "$2")
  if [ -z "$xy" ]; then dismiss_prompts; xy=$(find_xy "$1" "$2"); fi
  if [ -z "$xy" ]; then echo "-- not on screen: $2"; shot "missing-$(echo "$2" | tr -c 'A-Za-z0-9' _)"; return 1; fi
  adb shell input tap $xy; sleep 2
}
visible() { find_xy "$1" "$2" | grep -q .; }
step() { echo "=== $1"; }

adb install -r app.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12; shot home; check "launch"

step "tabs"
tap exact Downloads; shot downloads; check "Downloads tab"
tap exact Home; shot home-again; check "Home tab"

step "settings and themes"
tap exact Settings; shot settings; check "open settings"
tap contains "Theme"; shot theme-dialog
tap contains "AMOLED"; sleep 3; shot amoled; check "AMOLED theme"
tap exact Settings; tap contains "Theme"; tap exact Dark; sleep 3; shot dark; check "Dark theme"
tap exact Settings; tap contains "Theme"; tap exact Light; sleep 3; shot light; check "Light theme"
tap exact Settings; tap contains "ad blocker"; shot adblock-dialog; check "ad blocker dialog"
adb shell input keyevent 4; sleep 1
tap contains "fastest server"; shot fastest-toggle; check "fastest toggle"
adb shell input keyevent 4; sleep 1

step "night mode"
adb shell cmd uimode night yes; sleep 4; shot night-home; check "system night mode"
tap exact Downloads; shot night-downloads; check "night Downloads"
tap exact Home; tap exact Settings; shot night-settings; check "night settings"
adb shell input keyevent 4; sleep 1
adb shell cmd uimode night no; sleep 3

step "a file download"
tap exact Home
tap id input && adb shell input text "https://proof.ovh.net/files/10Mb.dat"
shot link-typed
tap exact Download; sleep 15; shot after-download; check "start download"
tap exact Downloads; shot list-after-download; check "list after download"
visible contains "10Mb" && echo "download is listed" || echo "-- download NOT listed"

step "files shared in"
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://proof.ovh.net/files/1Mb.dat" -n $PKG/bot.paradox.downloader.MainActivity >/dev/null 2>&1
sleep 12; shot shared-link; check "share a link in"

step "a web page opens in the browser"
tap exact Home
tap id input && adb shell input text "https://example.com"
tap exact Download
# The first yt-dlp lookup can take a minute before the app offers the browser.
for _ in $(seq 1 30); do visible exact "Open browser" && break; sleep 3; done
shot page-result; check "resolve a page"
tap exact "Open browser"; sleep 12; shot browser; check "open browser"
tap contains "ad blocker"; shot browser-shield; check "toggle blocker"
adb shell input keyevent 4; sleep 2; check "leave browser"

echo "=== done, no crash"
adb logcat -d -b crash | grep -E "$CRASHED" || echo "no app crash in the crash buffer"
