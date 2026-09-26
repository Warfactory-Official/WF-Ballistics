package com.wf.wflib.rail.excavate;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * One kind of cell of a {@link TunnelProfile}, swept along a route.
 *
 * <p>The profile is the section and the corridor is the path; this is what turns the pair into
 * something the carve can ask "is this block one of yours". The same profile and path produce the bore
 * volume and the lining volume, so the two can never disagree about where the wall is.</p>
 *
 * <p>What happens in the one-block slab past each end is the {@link End} of that end, and there are
 * three answers because a route stops for three different reasons. See {@link End}.</p>
 */
public final class ProfileVolume implements CarveVolume {

    /** How far past each end of the route the end wall sits, in blocks. */
    private static final double END_SLAB = 1.0;

    /**
     * How far past an {@link End#OPEN} end the volume reaches, in blocks.
     *
     * <p>One block of tunnel and then the wall, rather than the wall straight away. An open end still
     * has to present a sealed face to whatever is on the other side of it, because until the railway it
     * joins is dug there is nothing there but rock, and the whole reason a route's face is walled is
     * that the rock is sometimes water. The other route's bore takes that wall out when it arrives; it
     * is never allowed to take out track, which is what makes leaving it there safe.</p>
     */
    private static final double OPEN_SLAB = 2.0;

    /**
     * What the slab past one end of a swept volume is.
     *
     * <p>A tunnel ends for three different reasons and they want three different walls, which is why
     * this is not a boolean. Getting it wrong is invisible: the tunnel finishes, and the thing that was
     * supposed to be on the other side of the end is simply walled off from it.</p>
     */
    public enum End {
        /**
         * A face. Everything the section covers past this end becomes lining, tunnel cells included,
         * which is what stops a bore that ends inside an aquifer filling from its own face.
         */
        CAP,
        /**
         * A boundary inside a longer tunnel: the volume stops exactly at the end and claims nothing
         * past it, because the next stretch is what is there.
         */
        FLUSH,
        /**
         * Another railway's tunnel carries on here, so this one runs a block into it rather than
         * walling itself off from it.
         *
         * <p>This is what a junction is made of. Where two routes meet end to end, each one's face is
         * the other one's tunnel: a cap there is a plug in the middle of what should be one railway,
         * and it is invisible from either route because each of them is perfectly built right up to
         * it. The block <em>overlap</em> matters as much as the missing wall - the block a shared
         * endpoint falls in belongs to whichever route's centreline claims it, and with both ends
         * stopping flush it can end up claimed by neither and left as rock.</p>
         */
        OPEN
    }

    private final CarveVolume.Corridor path;
    private final TunnelProfile profile;
    private final TunnelProfile.Kind want;
    private final int floorY;
    private final End atStart;
    private final End atFinish;
    private final double halfWidth;
    private final double endAllowance;
    private final BoundingBox bounds;

    public ProfileVolume(CarveVolume.Corridor path, TunnelProfile profile, TunnelProfile.Kind want,
                         int floorY, boolean capEnds) {
        this(path, profile, want, floorY, capEnds ? End.CAP : End.FLUSH,
                capEnds ? End.CAP : End.FLUSH);
    }

    public ProfileVolume(CarveVolume.Corridor path, TunnelProfile profile, TunnelProfile.Kind want,
                         int floorY, End atStart, End atFinish) {
        this.path = path;
        this.profile = profile;
        this.want = want;
        this.floorY = floorY;
        this.atStart = atStart;
        this.atFinish = atFinish;
        this.halfWidth = profile.width() / 2.0;
        // Always the same allowance, whichever kind this volume is for. The bore and the lining have to
        // agree about which part of the route a block belongs to, and they only can if they project it
        // the same way; deciding what to do with the end slab afterwards is what differs between them.
        this.endAllowance = atStart == End.OPEN || atFinish == End.OPEN ? OPEN_SLAB : END_SLAB;

        double[] xs = path.xs();
        double[] zs = path.zs();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        double reach = this.halfWidth + this.endAllowance + 1.0;
        this.bounds = new BoundingBox(
                (int) Math.floor(minX - reach), floorY + profile.lowestUp(),
                (int) Math.floor(minZ - reach),
                (int) Math.ceil(maxX + reach), floorY + profile.highestUp(),
                (int) Math.ceil(maxZ + reach));
    }

    @Override
    public BoundingBox bounds() {
        return this.bounds;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        int up = y - this.floorY;
        if (up < this.profile.lowestUp() || up > this.profile.highestUp()) {
            return false;
        }
        CarveVolume.Corridor.Local local = this.path.localAt(x + 0.5, z + 0.5, this.endAllowance);
        if (local == null) {
            return false;
        }
        int across = (int) Math.floor(local.offset() + this.halfWidth);
        TunnelProfile.Kind kind = this.profile.at(across, up);
        if (kind == TunnelProfile.Kind.NONE) {
            return false;
        }
        if (local.chainage() < 0.0) {
            return past(this.atStart, kind, -local.chainage());
        }
        if (local.chainage() > this.path.length()) {
            return past(this.atFinish, kind, local.chainage() - this.path.length());
        }
        return inside(kind);
    }

    /**
     * What the section means in the slab past one end of the route.
     *
     * @param over how far past the end of the route this block is, in blocks
     */
    private boolean past(End end, TunnelProfile.Kind kind, double over) {
        return switch (end) {
            case CAP -> this.want == TunnelProfile.Kind.LINING;
            case FLUSH -> false;
            // An open end is not an end. The first block past it is tunnel exactly as anywhere else,
            // walls and floor included, so what two routes share at a junction is a block of finished
            // tunnel rather than the rock neither of their centrelines quite reached into. The wall is
            // not dropped, only moved out of the way: it stands a block further on.
            case OPEN -> over <= END_SLAB ? inside(kind) : this.want == TunnelProfile.Kind.LINING;
        };
    }

    /** What the section means anywhere along the route itself. */
    private boolean inside(TunnelProfile.Kind kind) {
        // A torch cell is a tunnel cell that happens to get a torch put in it afterwards.
        return this.want == TunnelProfile.Kind.BORE ? kind.inside() : kind == this.want;
    }
}
