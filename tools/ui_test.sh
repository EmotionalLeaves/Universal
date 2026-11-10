#!/bin/bash
# Drives the app in an emulator like a person would and saves a screenshot after every step.
# Usage: tools/ui_test.sh <apk> [scenario]
APK="$1"
OUT=ui-test
mkdir -p $OUT
PKG=com.emotionalleaves.universe
n=0

log() { echo "== $*" | tee -a $OUT/steps.txt; }
shot() { n=$((n+1)); f=$(printf "%s/%02d_%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; log "shot $f"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
# Centre of the first node whose text or content-desc equals $1 (or contains it with $2=contains).
find_xy() {
  dump
  python3 - "$1" "${2:-exact}" <<'PY'
import sys, re, xml.etree.ElementTree as ET
want, mode = sys.argv[1], sys.argv[2]
try:
    root = ET.parse("ui-test/ui.xml").getroot()
except Exception:
    sys.exit(1)
hits = []
for n in root.iter("node"):
    for key in ("text", "content-desc"):
        v = n.get(key, "")
        if (v == want) or (mode == "contains" and want.lower() in v.lower()):
            b = re.findall(r"\d+", n.get("bounds", ""))
            if len(b) == 4:
                hits.append((list(map(int, b)), n.get("clickable"), n.get("class")))
if len(hits) > 1:
    with open("ui-test/steps.txt", "a") as f:
        f.write("   %d matches for %r: %s\n" % (len(hits), want, hits))
if not hits:
    sys.exit(1)
(x1, y1, x2, y2), _, _ = hits[0]
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}
tap_text() { xy=$(find_xy "$1" "$2"); if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; else log "NOT FOUND: '$1'"; fi; }
hold_text() { xy=$(find_xy "$1"); if [ -n "$xy" ]; then set -- $xy "$2"; adb shell input swipe $1 $2 $1 $2 $3; log "hold at $1,$2 for $3 ms"; else log "NOT FOUND (hold)"; fi; }
drag() { adb shell input swipe $1 $2 $3 $4 ${5:-500}; log "drag $1,$2 -> $3,$4"; }
texts() { dump; python3 - <<'PY' >> ui-test/steps.txt
import xml.etree.ElementTree as ET
try:
    r = ET.parse("ui-test/ui.xml").getroot()
    t = [n.get("text") or n.get("content-desc") for n in r.iter("node") if (n.get("text") or n.get("content-desc"))]
    print("   on screen:", " | ".join(t[:60]))
except Exception as e:
    print("   (no ui dump)", e)
PY
}

adb install -r "$APK" | tee -a $OUT/steps.txt
adb shell wm size | tee -a $OUT/steps.txt
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 40
shot launch; texts
tap_text "Skip"; sleep 3; shot home; texts

# Orbit around Earth with one finger
drag 300 1300 800 1300 700; sleep 2; shot rotated
# Tap the middle of the screen (Earth) to select it
adb shell input tap 540 1200; sleep 3; shot tap_center; texts

# Explore -> Mars
tap_text "Explore"; sleep 3; shot explore; texts
tap_text "Mars"; sleep 12; shot at_mars; texts
tap_text "Read more & all facts"; sleep 2; shot card_expanded
tap_text "Show less"; sleep 1
drag 300 1000 800 1000 800; sleep 2; shot orbit_drag
tap_text "Show card"; sleep 1; shot card_back

# Close the card
tap_text "Close"; sleep 1
# Zoom out through the scales with the slider labels
for s in "Planet" "Solar system" "Nearby stars" "Milky Way" "Galaxy groups" "Universe" "Beyond"; do
  tap_text "$s"; sleep 6; shot "zoom_${s// /_}"; texts
done
# View filters: real only at the galaxy-groups scale, then everything again
tap_text "Galaxy groups"; sleep 6
tap_text "Filters"; sleep 2; shot filters; texts
tap_text "Real only"; sleep 1; shot filters_real; tap_text "Back"; sleep 4; shot real_only_groups
tap_text "Filters"; sleep 2; tap_text "Everything"; sleep 1; tap_text "Back"; sleep 3
# One structure on its own, then everything again; the in-view list
tap_text "Filters"; sleep 2; tap_text "Pick a structure"; sleep 2; shot structures; texts
tap_text "Virgo Supercluster"; sleep 14; shot only_virgo_supercluster; texts
tap_text "Show everything"; sleep 3; shot everything_again
tap_text "In view"; sleep 3; shot in_view; texts
tap_text "Back"; sleep 2

# Home, then free flight
tap_text "Home"; sleep 8; shot home_again; texts
tap_text "Free flight"; sleep 2; shot free_on; texts
drag 300 1200 700 1000 700; sleep 2; shot free_look
hold_text "Forward" 4000; sleep 1; shot free_forward; texts
tap_text "×1"; sleep 1; tap_text "×10"; sleep 1; hold_text "Forward" 3000; sleep 1; shot free_warp; texts
tap_text "Go to nearest"; sleep 8; shot free_nearest; texts
tap_text "Go to nearest"; sleep 8; shot free_nearest2; texts
tap_text "Exit"; sleep 2; shot free_exit

# Search
tap_text "Search"; sleep 3; shot search_empty; texts
adb shell input text "ton%s618"; sleep 5; shot search; texts
adb shell input keyevent 66; log "enter"; sleep 12; shot ton618; texts
tap_text "Compare size"; sleep 3; shot sizes; texts
adb shell input keyevent 4; sleep 2

# Menu and census
sleep 2; tap_text "Menu"; sleep 3; shot menu; texts
tap_text "What's in the universe"; sleep 2; shot census; texts
adb shell input keyevent 4; sleep 2; shot after_back; texts

# Explore a far galaxy and a black hole
tap_text "Explore"; sleep 2; tap_text "Black hole"; sleep 12; shot black_hole; texts
tap_text "Explore"; sleep 2; tap_text "Galaxy"; sleep 12; shot random_galaxy; texts

# Gaia: built-in stars near the Sun, then neighbourhoods streamed on arrival (times in logcat).
tap_text "Search"; sleep 3
adb shell input text "Pleiades"; sleep 4; adb shell input keyevent 66; log "enter"; sleep 10; shot gaia_pleiades; texts
tap_text "Search"; sleep 3
adb shell input text "Orion%sNebula"; sleep 4; adb shell input keyevent 66; log "enter"; sleep 10; shot gaia_orion; texts
tap_text "Search"; sleep 3
adb shell input text "Deneb"; sleep 4; adb shell input keyevent 66; log "enter"; sleep 20; shot gaia_deneb; texts

# Every known object: online name lookup (not in the app), the asteroid download, Gaia streaming.
tap_text "Search"; sleep 3
adb shell input text "Sirius%sB"; sleep 14; shot search_online; texts
adb shell input keyevent 66; log "enter"; sleep 14; shot online_siriusb; texts
tap_text "Search"; sleep 3
adb shell input text "2024%sYR4"; sleep 14; shot search_yr4; texts
adb shell input keyevent 66; log "enter"; sleep 14; shot online_yr4; texts
tap_text "Menu"; sleep 3; tap_text "Data packs"; sleep 3; shot data_packs; texts
tap_text "Download"; sleep 90; shot asteroid_download; texts
sleep 150; shot asteroid_download2; texts
adb shell input keyevent 4; sleep 2
tap_text "Home"; sleep 6
tap_text "Solar system"; sleep 8; shot all_asteroids_solar; texts

adb logcat -d > $OUT/logcat.txt
grep -E "FATAL|E AndroidRuntime|GL_INVALID|UniverseProblem|UniverseGL|UniverseDiag" $OUT/logcat.txt | head -120 > $OUT/errors.txt || true
adb shell run-as $PKG cat files/last_problem.txt > $OUT/last_problem.txt 2>/dev/null || true
log "done"
