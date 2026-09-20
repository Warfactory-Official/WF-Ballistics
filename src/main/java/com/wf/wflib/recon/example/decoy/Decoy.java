package com.wf.wflib.recon.example.decoy;

/**
 * A radar decoy: a target with no entity, no simulation tick, and no state that changes.
 *
 * @param id per-level counter, mapped into the decoy id space
 * @param spawnTick game time it was released at; positions are computed from this
 * @param expiresAt game time it stops existing
 * @param rcs what it presents before anything it carries has its say
 */
public record Decoy(int id, double x0, double y0, double z0,
                    double vx, double vy, double vz,
                    float rcs, long spawnTick, long expiresAt) {

    public double xAt(long gameTime) {
        return x0 + vx * (gameTime - spawnTick);
    }

    public double yAt(long gameTime) {
        return y0 + vy * (gameTime - spawnTick);
    }

    public double zAt(long gameTime) {
        return z0 + vz * (gameTime - spawnTick);
    }

    public boolean alive(long gameTime) {
        return gameTime < expiresAt;
    }
}
