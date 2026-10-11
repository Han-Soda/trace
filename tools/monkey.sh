#!/usr/bin/env bash
# Thousands of random taps, swipes and key presses on Trace, first on a fresh start and then
# with a trip loaded, to catch crashes and freezes. Run from the project on a running emulator;
# exits non-zero on any crash or "not responding".
set -u
APP=io.github.hansoda.trace
OUT="${RUNNER_TEMP:-/tmp}/monkey.txt"
: > "$OUT"

./gradlew installDebug -q || exit 1
adb shell pm clear "$APP" > /dev/null

monkey() {
    adb shell monkey -p "$APP" --pct-syskeys 0 --pct-appswitch 0 --throttle 60 -s "$1" -v "$2" >> "$OUT" 2>&1
}

monkey 1 2000
# A trip, saved into Trace's inbox the way Google's export saves it, and the editor open on it.
adb shell am force-stop "$APP"
adb shell "run-as $APP mkdir -p files/inbox"
adb shell "run-as $APP sh -c 'cat > files/inbox/Timeline.json'" < play-store/sample-timeline.json
adb shell "run-as $APP touch files/inbox/Timeline.json.finished"
adb shell am start -W -n "$APP/.MainActivity" > /dev/null
sleep 10
monkey 2 4000
monkey 3 4000

grep -c "Sending event" "$OUT" | sed 's/^/events: /'
if grep -E "CRASH|NOT RESPONDING|Monkey aborted" "$OUT"; then
    grep -A 30 -E "CRASH|NOT RESPONDING" "$OUT" | head -80
    # What Android itself kept of the crash, for the log.
    { echo; echo "--- logcat crash buffer"; adb logcat -b crash -d; } >> "$OUT" 2>&1
    exit 1
fi
echo "No crashes or freezes."
