#!/usr/bin/env bash
# Runtime harness for the JFR attribution pass. Same shape as drone.sh -- superflat bench world on a
# throwaway level-name, rcon over a fifo-held stdin, server.properties restored on the way out -- with
# -Pjfr added so the run is attachable and DebugNonSafepoints is on.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJ=$(cd "$HERE/../.." && pwd)
TMP=${WF_BENCH_OUT:-$HERE}
PROPS=$PROJ/run/server.properties
BAK=$TMP/server.properties.jfrbak
WORLD=jfrbench
FIFO=$TMP/jfr-stdin.fifo
LOG=$TMP/jfr-server.log
PROBE=${1:-$TMP/jfrbench.py}
OUT=${2:-$TMP/jfrbench.out}

cleanup() {
    echo "--- cleanup ---"
    if [ -p "$FIFO" ]; then echo "stop" > "$FIFO" 2>/dev/null || true; fi
    for _ in $(seq 1 40); do
        ss -ltn 2>/dev/null | grep -qE ':(25565|25575)\b' || break
        sleep 2
    done
    pkill -f 'net.neoforged.devlaunch.Main' 2>/dev/null || true
    sleep 2
    [ -f "$BAK" ] && cp "$BAK" "$PROPS" && rm -f "$BAK"
    rm -f "$FIFO"
    rm -rf "$PROJ/run/$WORLD"
    echo "--- restored ---"
    grep -E 'enable-rcon|rcon.password|level-name|online-mode|level-type|difficulty' "$PROPS"
}
trap cleanup EXIT

if ss -ltn 2>/dev/null | grep -qE ':(25565|25575)\b'; then
    echo "FATAL: 25565/25575 already held; an orphan server would answer rcon instead of this build."
    ss -ltnp 2>/dev/null | grep -E ':(25565|25575)\b'
    trap - EXIT
    exit 1
fi

cp "$PROPS" "$BAK"
python3 - "$PROPS" "$WORLD" <<'PY'
import sys
path, world = sys.argv[1], sys.argv[2]
want = {
    "enable-rcon": "true", "rcon.password": "bench", "rcon.port": "25575",
    "level-name": world, "level-type": "minecraft:flat", "online-mode": "false",
    "difficulty": "normal", "generate-structures": "false",
    "spawn-monsters": "false", "spawn-animals": "false", "spawn-npcs": "false",
    "max-tick-time": "-1",
}
lines, seen = [], set()
for line in open(path):
    k = line.split("=", 1)[0].strip()
    if k in want:
        lines.append(f"{k}={want[k]}\n"); seen.add(k)
    else:
        lines.append(line)
for k, v in want.items():
    if k not in seen:
        lines.append(f"{k}={v}\n")
open(path, "w").writelines(lines)
PY
rm -rf "$PROJ/run/$WORLD"

rm -f "$FIFO"; mkfifo "$FIFO"
cd "$PROJ"
( sleep infinity > "$FIFO" ) &
HOLDER=$!
nohup ./gradlew runServer -Pjfr --console=plain < "$FIFO" > "$LOG" 2>&1 &
echo "server pid $! (holder $HOLDER), log $LOG, probe $PROBE"

python3 "$PROBE" 2>&1 | tee "$OUT"
STATUS=${PIPESTATUS[0]}
kill $HOLDER 2>/dev/null || true
exit "$STATUS"
