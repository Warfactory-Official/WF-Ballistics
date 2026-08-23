# Swarm profiling harness

Names what `SwarmProfiler` can only measure. The profiler brackets specific methods, so its residuals —
the 48.8% of a glyphid tick that had no call site in it — cannot be broken up by adding more call sites to
the places you already suspect. JFR samples the stack instead, so it names frames nobody instrumented.

```bash
./jfr.sh                        # boots a bench server with -Pjfr, records base.jfr and swarm.jfr
python3 jfranalyse.py swarm.jfr # phase tree, residual breakdowns, folded stacks for a flamegraph

./lith.sh                       # A/B Lithium's default-off groups, one server boot per arm

./jfr.sh behave.py out.txt      # behaviour regression check for the applied glyphid fixes
./abprey.sh                     # runs that same check with and without the change, via git stash
```

`abprey.sh` is the pattern worth reusing: **"did this change behaviour" is a question with a control**, and
the control is the same probe against the unchanged tree. Its own prey arm reports that cows forty blocks
away die — which looks like a broken targeting range until the before arm reports exactly the same thing,
because a rallied swarm still wanders. An absolute expectation would have condemned working code.

`jfr.sh` edits `run/server.properties` (rcon, a throwaway `level-name`, flat world) and **restores it on
exit**, including on Ctrl-C. It refuses to start if 25565/25575 are already held, because an orphan server
would answer rcon and the harness would silently measure the previous build.

Environment: `WF_JDK21` (default `/usr/lib/jvm/java-21-openjdk/bin`) must be the JDK the game runs on — a
recording written by 21 does not parse with 17's `jfr`, and the failure is an empty parse rather than an
error. `WF_BENCH_OUT` moves the recordings off the source tree.

## Reading the output

`jfranalyse.py` charges each sample to the innermost `SwarmProfiler` phase marker on its stack, which is
the rule a nested begin/end pair already follows — so the two views describe one tree and can be checked
against each other. **Check that first.** If JFR and the profiler disagree on a phase by more than a few
percent the mapping in `PHASES` is stale (a mixin moved, or vanilla renamed a method) and nothing
underneath it means anything. The last agreement is recorded in `docs/PERFORMANCE.md`: ten phases, none off
by more than 0.06 ms.

Each residual is then broken open twice, because the two answer different questions: by the callee directly
under the marker (*which sub-call is this*) and by the leaf frame (*what is it executing*).

## Gotchas this harness already has guards for

- `/forceload add` refuses more than 256 chunks per command, on a reply line a script will not read.
- `forceload query` answers "is **marked for** force loading", not "is force loaded". A precondition check
  that is wrong pessimistically still ruins a run.
- `swarmbench spawn` calls `releaseArena()` first, so a corridor forceloaded before it is gone after it.
- `swarmbench clear` releases the arena *and* turns profiling off.
- `swarmbench watcher` takes three coordinates. With two it is refused and says so quietly.
- JFR's default `stackdepth=64` truncates the *outermost* frames — the ones attribution needs. `-Pjfr`
  sets 192.
- **Waiting for the ports to clear is not waiting for the server to exit.** A stopping server releases its
  sockets and then spends minutes saving a forceloaded world. The first run of `lith.sh` booted each arm on
  top of the previous arm's still-running JVM — two servers writing one save — and the result was an arm
  that read "this flag does nothing" when a clean re-run showed it was worth 13%. `stop_server` now waits
  for the *process*, then SIGKILLs, and every arm refuses to boot while a game JVM is alive.
- **`pkill -f <class name>` matches the shell running the pkill.** It kills the killer and leaves the
  servers up. Match on `pgrep -x java` plus the cmdline instead.

See `docs/PERFORMANCE.md` for what the numbers were last time, and the memory note
`headless-benchmarks-lie-silently` for the longer list.
