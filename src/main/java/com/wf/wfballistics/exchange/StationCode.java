package com.wf.wfballistics.exchange;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * The 12-character identifier a station is known by.
 *
 * <p>A code is the <em>only</em> thing that travels between players. It is a lookup key into a server-side
 * registry and carries no information about where the station is, which is the whole point: you can hand
 * someone your code across chat, in the open, and all they can do with it is send you things.
 *
 * <p>The alphabet is Crockford's base32: the digits and letters minus {@code I}, {@code L}, {@code O} and
 * {@code U}, so there is no pair of glyphs that can be confused when read off a screen and typed back in.
 * {@link #normalise} maps the confusable characters onto their intended ones, so someone who types a letter
 * O where a zero was meant still gets the right station instead of a mysterious failure. Twelve characters of
 * a 32-symbol alphabet is 60 bits: not guessable, and not worth trying to enumerate.
 */
public final class StationCode {

    public static final int LENGTH = 12;
    /**
     * Crockford base32. No I, L, O or U: the first three are confusable with 1 and 0, and dropping U keeps
     * the alphabet from spelling anything unfortunate.
     */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /**
     * Codes decide who may send you cargo, so they are drawn from a cryptographic source rather than the
     * level's shared, seeded, entirely predictable {@code RandomSource}.
     */
    private static final SecureRandom SECURE = new SecureRandom();

    private StationCode() {
    }

    public static String generate() {
        return generate(SECURE);
    }

    /**
     * @param rng exposed so the self-test can generate deterministically; production uses {@link #generate()}
     */
    public static String generate(RandomGenerator rng) {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /**
     * Clean up a code as typed by a human: upper-case it, drop separators and spaces, and fold the
     * confusable glyphs onto the ones the alphabet actually uses.
     *
     * @return the normalised code, which may still be invalid: check with {@link #valid}
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(LENGTH);
        for (char c : raw.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            switch (c) {
                case '-', ' ', '_', '.' -> {
                    // Separators people add to make a long code readable.
                }
                case 'I', 'L' -> out.append('1');
                case 'O' -> out.append('0');
                case 'U' -> out.append('V');
                default -> {
                    if (ALPHABET.indexOf(c) >= 0) {
                        out.append(c);
                    } else {
                        // Anything else is a typo; keep it so validation rejects rather than silently
                        // resolving to some other station.
                        out.append('?');
                    }
                }
            }
        }
        return out.toString();
    }

    public static boolean valid(String code) {
        if (code == null || code.length() != LENGTH) {
            return false;
        }
        for (char c : code.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the code split into groups of four for display. Never used for lookup: {@link #normalise}
     * strips the separators straight back out.
     */
    public static String pretty(String code) {
        if (!valid(code)) {
            return code == null ? "" : code;
        }
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12);
    }
}
