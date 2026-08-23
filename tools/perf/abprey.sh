#!/usr/bin/env bash
# Behaviour A/B for the prey index: run the same probe against the tree with and without the change.
#
# The probe reports that cows forty blocks away die, which is outside PREY_RANGE. By construction that
# cannot be the index's doing -- it scans a different set through the identical box test -- so the
# expectation is that the old code does exactly the same thing, and the difference is that a rallied swarm
# still wanders. Asserting that would be cheaper than measuring it and worth much less.
#
# The BEFORE arm is produced with `git stash -u`. If this script is interrupted between the stash and the
# pop, the changes are not lost: `git stash list` will show them.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJ=$(cd "$HERE/../.." && pwd)
TMP=${WF_BENCH_OUT:-$HERE}
STASHED=0

game_jvms() {
    for pid in $(pgrep -x java); do
        if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'devlaunch'; then echo "$pid"; fi
    done
}

restore() {
    if [ "$STASHED" = "1" ]; then
        echo "--- restoring the working tree ---"
        (cd "$PROJ" && git stash pop) && STASHED=0
    fi
}
trap restore EXIT

if [ -n "$(game_jvms)" ] || ss -ltn 2>/dev/null | grep -qE ':(25565|25575)\b'; then
    echo "FATAL: a game JVM or its ports are already up"
    trap - EXIT
    exit 1
fi

run_arm() {
    local name=$1
    echo "=============================================================="
    echo "ARM $name  ($(date +%H:%M:%S))"
    "$HERE/jfr.sh" "$HERE/behave.py" "$TMP/behave-$name.out" > "$TMP/behave-$name.run" 2>&1
    echo "--- $name ---"
    grep -E "^\s+(placed|after|\[PASS\]|\[FAIL\])|behaviour checks" "$TMP/behave-$name.out" || true
    for _ in $(seq 1 90); do [ -z "$(game_jvms)" ] && break; sleep 2; done
    for pid in $(game_jvms); do echo "  killing lingering JVM $pid"; kill -9 "$pid"; done
    sleep 5
}

cd "$PROJ"
echo "### stashing the change to build the BEFORE arm"
git stash push -u -m "abprey-before" -- \
    src/main/java/com/wf/wfballistics/entity/glyphid/EntityGlyphid.java \
    src/main/java/com/wf/wfballistics/entity/glyphid/PreyTracker.java \
    src/main/java/com/wf/wfballistics/mixin/MixinLivingEntity.java \
    src/main/resources/wfballistics.mixins.json
STASHED=1
git status --short

run_arm before
restore
git status --short
run_arm after

echo
echo "=============================================================="
echo "=== the one line that matters, in both arms ==="
for arm in before after; do
    printf "%-8s " "$arm"
    grep -E "prey out of range" "$TMP/behave-$arm.out" || echo "(missing)"
done
