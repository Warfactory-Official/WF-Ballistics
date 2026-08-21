#!/usr/bin/env python3
"""
Synthesise a seamless quadcopter rotor loop.

Everything is built on a frequency grid of 1/T Hz, so every partial completes a whole number of
cycles inside the buffer and the loop point is mathematically continuous. Noise is generated in the
frequency domain for the same reason: an inverse FFT of a shaped spectrum is periodic by construction,
so the "air" layer wraps as cleanly as the tones do.

Layers:
  1. blade-pass tones   - four rotors, each a harmonic stack on its own blade-pass frequency, detuned
                          from each other so they beat the way four motors trimming an attitude do
  2. motor whine        - BLDC commutation, 7 pole pairs, i.e. 3.5x the blade-pass frequency
  3. aerodynamic noise  - broadband air, amplitude-modulated by the blade pulses (the "chop")

The result is voiced at an unladen hover, because that is the anchor DroneRotorSound pitches around:
it plays this back at the airframe's own rotor scale, so the loop only sounds "correct" at 1.0 if a
hovering drone is what it was tuned against.

Run it to regenerate assets/wfballistics/sounds/drone/rotor_loop.ogg. Needs numpy and ffmpeg.
"""

import math
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
T = 2.0                      # loop length, seconds
N = int(SR * T)
GRID = 1.0 / T               # every frequency must be a multiple of this to loop seamlessly

t = np.arange(N) / SR
rng = np.random.default_rng(20250820)


def snap(f):
    """Nearest frequency that completes a whole number of cycles in the buffer."""
    return round(f / GRID) * GRID


def partial(freq, amp, phase):
    return amp * np.sin(2.0 * math.pi * snap(freq) * t + phase)


# --- 1. blade-pass tones -------------------------------------------------------------------------
# Nominal hover blade-pass ~165 Hz (two-blade props at roughly 5000 rpm). The four rotors sit a few
# percent apart, which is what produces the restless beating a quad has and a single tone does not.
ROTORS = [158.5, 163.0, 166.5, 171.5]
WOBBLE = [1.5, 2.0, 2.5, 1.0]     # slow per-rotor amplitude drift, Hz (on-grid)
HARMONICS = 14

tones = np.zeros(N)
for rotor, (bpf, wob) in enumerate(zip(ROTORS, WOBBLE)):
    stack = np.zeros(N)
    for n in range(1, HARMONICS + 1):
        # Rotor noise concentrates in the first few blade-pass harmonics and falls away above them.
        amp = n ** -1.05
        stack += partial(bpf * n, amp, rng.uniform(0.0, 2.0 * math.pi))
    # Each rotor breathes a little on its own slow cycle rather than holding a dead-steady level.
    stack *= 1.0 + 0.14 * np.sin(2.0 * math.pi * snap(wob) * t + rng.uniform(0.0, 2.0 * math.pi))
    tones += stack
tones /= np.max(np.abs(tones))

# --- 2. motor whine ------------------------------------------------------------------------------
# The electrical side: commutation at pole_pairs x revolutions, and two-blade props turn at half the
# blade-pass rate, so this lands at 3.5x BPF - the thin electric edge on top of the rotor buzz.
POLE_PAIRS = 7
whine = np.zeros(N)
for bpf in ROTORS:
    fundamental = bpf * 0.5 * POLE_PAIRS
    for n, amp in ((1, 1.0), (2, 0.45), (3, 0.20)):
        whine += partial(fundamental * n, amp, rng.uniform(0.0, 2.0 * math.pi))
whine /= np.max(np.abs(whine))

# --- 3. aerodynamic noise ------------------------------------------------------------------------
# Periodic noise: shape a spectrum, inverse transform it. No filter state to carry across the seam.
freqs = np.fft.rfftfreq(N, 1.0 / SR)
spec = np.fft.rfft(rng.standard_normal(N))

with np.errstate(divide="ignore"):
    envelope = np.zeros_like(freqs)
    nz = freqs > 0
    # Pink-ish tilt, rolled off below the rotor disc and above the range where prop wash lives.
    envelope[nz] = freqs[nz] ** -0.5
    envelope *= 1.0 / (1.0 + (170.0 / np.maximum(freqs, 1.0)) ** 4)      # high-pass ~170 Hz
    envelope *= 1.0 / (1.0 + (freqs / 9500.0) ** 3)                      # low-pass ~9.5 kHz
    # Broad rush of moving air, where a quad's "whoosh" actually sits.
    envelope *= 1.0 + 1.4 * np.exp(-0.5 * (np.log(np.maximum(freqs, 1.0) / 1600.0) / 0.75) ** 2)

noise = np.fft.irfft(spec * envelope, n=N)
noise /= np.max(np.abs(noise))

# The chop: every blade passing the airframe cuts the airflow, so the broadband layer pulses at each
# rotor's blade-pass rate. This is what stops the noise reading as plain hiss laid under a tone.
chop = np.zeros(N)
for bpf in ROTORS:
    phase = 2.0 * math.pi * snap(bpf) * t + rng.uniform(0.0, 2.0 * math.pi)
    chop += 0.5 * (1.0 - np.cos(phase)) ** 2        # narrow raised-cosine pulse per blade
chop /= np.max(np.abs(chop))
gust = 0.5 * (1.0 + np.sin(2.0 * math.pi * snap(3.5) * t + rng.uniform(0.0, 2.0 * math.pi)))
noise *= 0.45 + 0.40 * chop + 0.15 * gust

# --- mix -----------------------------------------------------------------------------------------
mix = 1.00 * tones + 0.20 * whine + 0.78 * noise

# Voicing pass, done spectrally so it stays periodic. Trims the sub-bass nothing this size produces,
# lifts the presence region a small prop actually cuts through on, and tames the very top so Vorbis
# spends its bits on the buzz instead of on hiss.
mspec = np.fft.rfft(mix)
voice = np.ones_like(freqs)
voice *= 1.0 / (1.0 + (110.0 / np.maximum(freqs, 1.0)) ** 3)
voice *= 1.0 + 0.5 * np.exp(-0.5 * (np.log(np.maximum(freqs, 1.0) / 1300.0) / 0.55) ** 2)
voice *= 1.0 / (1.0 + (freqs / 11000.0) ** 4)
mix = np.fft.irfft(mspec * voice, n=N)

# Gentle saturation - memoryless, so the seam survives it - to glue the layers and add bite.
mix = np.tanh(1.5 * mix) / math.tanh(1.5)

mix -= mix.mean()                                   # no DC: OpenAL does not want it, nor does Vorbis
mix *= 0.85 / np.max(np.abs(mix))                   # headroom for the encoder to overshoot into

# --- seam check ----------------------------------------------------------------------------------
steps = np.abs(np.diff(mix))
seam = abs(mix[0] - mix[-1])
print(f"samples={N} peak={np.max(np.abs(mix)):.3f} rms={np.sqrt(np.mean(mix**2)):.3f}")
print(f"seam step={seam:.6f}  median step={np.median(steps):.6f}  max step={np.max(steps):.6f}")
assert seam <= np.max(steps), "loop point is discontinuous"

# --- write ---------------------------------------------------------------------------------------
ASSET = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "src", "main", "resources", "assets", "wfballistics",
                     "sounds", "drone", "rotor_loop.ogg")
wav = os.path.join(tempfile.gettempdir(), "wfb_rotor_loop.wav")

pcm = np.int16(np.clip(mix, -1.0, 1.0) * 32767)
with wave.open(wav, "wb") as w:
    w.setnchannels(1)          # MONO: OpenAL only pans and attenuates mono sources
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())

# Vorbis carries the exact sample count in its granule positions, so the decoded loop comes back the
# same length it went in and the seam survives the encode.
subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", wav, "-c:a", "libvorbis", "-q:a", "4", "-ac", "1", "-ar", str(SR),
                os.path.normpath(ASSET)], check=True)
print("wrote", os.path.normpath(ASSET))
