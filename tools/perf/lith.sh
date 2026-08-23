#!/usr/bin/env bash
# A/B Lithium's two default-off optimisation groups against the swarm.
#
# Lithium reads its options once at startup, so each arm needs its own server boot. The world is created
# once and kept between arms, so every arm marches the same 2000 bugs over the same already-generated
# corridor and the only thing that differs is lithium.properties.
#
# Precondition, asserted per arm rather than assumed: Lithium logs "N override(s) found" and logs a line
# for every option it turns off because a requirement was unmet. An arm whose overrides did not take is
# an arm measuring the previous configuration, and it would look exactly like "the flag did nothing".
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJ=$(cd "$HERE/../.." && pwd)
TMP=${WF_BENCH_OUT:-$HERE}
PROPS=$PROJ/run/server.properties
LITH=$PROJ/run/config/lithium.properties
BAK=$TMP/server.properties.lithbak
LITHBAK=$TMP/lithium.properties.lithbak
WORLD=lithbench
FIFO=$TMP/lith-stdin.fifo
RESULTS=$TMP/lith.json

cleanup() {
    echo "--- cleanup ---"
    stop_server
    [ -f "$BAK" ] && cp "$BAK" "$PROPS" && rm -f "$BAK"
    [ -f "$LITHBAK" ] && cp "$LITHBAK" "$LITH" && rm -f "$LITHBAK"
    rm -f "$FIFO"
    rm -rf "$PROJ/run/$WORLD"
    echo "--- restored ---"
    grep -E 'enable-rcon|level-name|level-type|online-mode|difficulty' "$PROPS"
    echo "lithium.properties: $(grep -cvE '^\s*#|^\s*$' "$LITH") override(s)"
}
trap cleanup EXIT

# Every game JVM currently alive. Matched on the executable plus the args, never with `pkill -f`: the
# pattern would also match the shell running the pkill, and killing the killer leaves the servers up.
game_jvms() {
    for pid in $(pgrep -x java); do
        if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'devlaunch'; then echo "$pid"; fi
    done
}

# Waiting for the ports to clear is NOT waiting for the server to exit. A stopping server releases its
# sockets and then spends minutes saving a 900-chunk world, so the next arm boots on top of it and two
# JVMs write the same save. That happened: arm L0's log was still growing five minutes into arm L1.
# Wait for the process, then SIGKILL, then prove nothing is left.
stop_server() {
    if [ -p "$FIFO" ]; then echo "stop" > "$FIFO" 2>/dev/null || true; fi
    for _ in $(seq 1 90); do
        [ -z "$(game_jvms)" ] && break
        sleep 2
    done
    for pid in $(game_jvms); do
        echo "  !! JVM $pid outlived its stop by 180s; killing"
        kill -9 "$pid" 2>/dev/null
    done
    [ -n "${HOLDER:-}" ] && kill "$HOLDER" 2>/dev/null
    rm -f "$FIFO"
    sleep 5
    if [ -n "$(game_jvms)" ]; then echo "  !! FATAL: a game JVM survived SIGKILL"; return 1; fi
    return 0
}

if [ -n "$(game_jvms)" ] || ss -ltn 2>/dev/null | grep -qE ":(25565|25575)\\b"; then
    echo "FATAL: 25565/25575 already held; an orphan server would answer rcon instead of this build."
    trap - EXIT
    exit 1
fi

cp "$PROPS" "$BAK"
cp "$LITH" "$LITHBAK"
python3 - "$PROPS" "$WORLD" <<'PY'
import sys
path, world = sys.argv[1], sys.argv[2]
want = {"enable-rcon": "true", "rcon.password": "bench", "rcon.port": "25575",
        "level-name": world, "level-type": "minecraft:flat", "online-mode": "false",
        "difficulty": "normal", "generate-structures": "false", "spawn-monsters": "false",
        "spawn-animals": "false", "spawn-npcs": "false", "max-tick-time": "-1"}
lines, seen = [], set()
for line in open(path):
    k = line.split("=", 1)[0].strip()
    lines.append(f"{k}={want[k]}\n" if k in want else line)
    seen.add(k) if k in want else None
for k, v in want.items():
    if k not in seen:
        lines.append(f"{k}={v}\n")
open(path, "w").writelines(lines)
PY
rm -rf "$PROJ/run/$WORLD" "$RESULTS"

# name | lithium.properties body
ARMS=(
"L0-defaults|"
"L1-ai.pathing|mixin.ai.pathing=true"
"L2-block_caching|mixin.util.block_tracking=true\nmixin.experimental=true"
"L3-both|mixin.ai.pathing=true\nmixin.util.block_tracking=true\nmixin.experimental=true"
)

FIRST=first
for entry in "${ARMS[@]}"; do
    NAME=${entry%%|*}
    BODY=${entry#*|}
    LOG=$TMP/lith-$NAME.log

    printf "# A/B arm %s\n" "$NAME" > "$LITH"
    [ -n "$BODY" ] && printf "%b\n" "$BODY" >> "$LITH"

    echo "=============================================================="
    echo "ARM $NAME  ($(date +%H:%M:%S))"
    sed 's/^/    /' "$LITH"

    # Refuse to boot on top of a survivor. An arm that shares its world with the previous arm's JVM is
    # measuring both of them, and it looks exactly like a flag that did nothing.
    if [ -n "$(game_jvms)" ] || ss -ltn 2>/dev/null | grep -qE ':(25565|25575)\b'; then
        echo "  !! ARM SKIPPED: a previous game JVM or its ports are still up"
        continue
    fi

    rm -f "$FIFO"; mkfifo "$FIFO"
    cd "$PROJ"
    ( sleep infinity > "$FIFO" ) &
    HOLDER=$!
    nohup ./gradlew runServer --console=plain < "$FIFO" > "$LOG" 2>&1 &

    python3 "$HERE/lithprobe.py" "$NAME" "$RESULTS" "$FIRST"
    FIRST=later

    echo "--- what Lithium says it did ---"
    grep -E "override\(s\) found|Setting 'mixin|Cached BlockState Flags" "$LOG" | sed 's/.*\] //' | sed 's/^/    /'
    stop_server
done

echo
echo "=============================================================="
echo "=== summary ==="
python3 - "$RESULTS" <<'PY'
import json, sys
arms = json.load(open(sys.argv[1]))
base = arms[0]
keys = ["entity tick", "ai step", "path search", "path following", "movement",
        "collision sweep", "fire scan", "base tick", "separation (level pass)"]
print(f"{'arm':<20}{'p50':>7}{'p95':>7}{'fired':>7}   vs L0")
for a in arms:
    d = a["p50"] - base["p50"] if a["p50"] and base["p50"] else float("nan")
    print(f"{a['label']:<20}{a['p50']:>7.2f}{a['p95']:>7.2f}{str(a['fired']):>7}   {d:+.2f} ms")
print()
print(f"{'phase':<32}" + "".join(f"{a['label'][:11]:>13}" for a in arms))
for k in keys:
    if any(k in a["phases"] for a in arms):
        print(f"{k:<32}" + "".join(f"{a['phases'].get(k, float('nan')):>13.3f}" for a in arms))
for k in sorted({k for a in arms for k in a["phases"] if k.startswith("(unattributed)")}):
    print(f"{k[:32]:<32}" + "".join(f"{a['phases'].get(k, float('nan')):>13.3f}" for a in arms))
PY
