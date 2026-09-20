package com.wf.wflib.exchange;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One arranged meeting between two stations at a neutral point. */
public final class Exchange {

    /** How long an arrangement stands before it is written off. */
    public static final long DEFAULT_LIFETIME_TICKS = 24000L;

    public enum Phase {
        /**
         * Agreed and waiting on the first drone.
         */
        ARRANGED,
        /**
         * At least one drone is flying, or cargo is on the ground waiting to be collected.
         */
        RUNNING,
        /** A player came within sight of the zone, or it timed out. */
        CANCELLED,
        COMPLETE
    }

    private final UUID id;
    private final ResourceKey<Level> dimension;
    private final Vec3 rendezvous;
    private final Party sender;
    private final Party recipient;
    private Phase phase = Phase.ARRANGED;
    private long deadline;
    private String note = "";

    public Exchange(UUID id, ResourceKey<Level> dimension, Vec3 rendezvous, Party sender, Party recipient,
                    long deadline) {
        this.id = id;
        this.dimension = dimension;
        this.rendezvous = rendezvous;
        this.sender = sender;
        this.recipient = recipient;
        this.deadline = deadline;
    }

    public UUID id() {
        return id;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public Vec3 rendezvous() {
        return rendezvous;
    }

    public Party sender() {
        return sender;
    }

    public Party recipient() {
        return recipient;
    }

    public Phase phase() {
        return phase;
    }

    public long deadline() {
        return deadline;
    }

    public String note() {
        return note;
    }

    public void extend(long deadline) {
        this.deadline = deadline;
    }

    public boolean active() {
        return phase == Phase.ARRANGED || phase == Phase.RUNNING;
    }

    public void begin() {
        if (phase == Phase.ARRANGED) {
            phase = Phase.RUNNING;
        }
    }

    public void cancel(String reason) {
        if (active()) {
            phase = Phase.CANCELLED;
            note = reason;
        }
    }

    public void complete() {
        if (active()) {
            phase = Phase.COMPLETE;
            note = "both sides settled";
        }
    }

    /**
     * @return the other side of this exchange, or null if the code belongs to neither.
     */
    @Nullable
    public Party other(String code) {
        if (sender.code().equals(code)) {
            return recipient;
        }
        return recipient.code().equals(code) ? sender : null;
    }

    @Nullable
    public Party party(String code) {
        if (sender.code().equals(code)) {
            return sender;
        }
        return recipient.code().equals(code) ? recipient : null;
    }

    public boolean involves(String code) {
        return sender.code().equals(code) || recipient.code().equals(code);
    }

    /**
     * @return true once neither side has anything left to fly.
     */
    public boolean settled() {
        return sender.settled(recipient) && recipient.settled(sender);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Dimension", dimension.location().toString());
        tag.putDouble("X", rendezvous.x);
        tag.putDouble("Y", rendezvous.y);
        tag.putDouble("Z", rendezvous.z);
        tag.put("Sender", sender.save());
        tag.put("Recipient", recipient.save());
        tag.putString("Phase", phase.name());
        tag.putLong("Deadline", deadline);
        tag.putString("Note", note);
        return tag;
    }

    public static Exchange load(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                dimensionId != null ? dimensionId : Level.OVERWORLD.location());
        Exchange exchange = new Exchange(tag.getUUID("Id"), dimension,
                new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                Party.load(tag.getCompound("Sender")), Party.load(tag.getCompound("Recipient")),
                tag.getLong("Deadline"));
        try {
            exchange.phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException ignored) {
            exchange.phase = Phase.CANCELLED;
        }
        exchange.note = tag.getString("Note");
        return exchange;
    }

    /**
     * One station's side of the meeting: what it owes, what it has delivered, and what it has picked up.
     */
    public static final class Party {

        private final String code;
        /**
         * True if this side has cargo to bring. Both sides may, which is what makes this a trade.
         */
        private final boolean sending;
        private boolean dispatched;
        @Nullable
        private UUID droppedCrate;
        private boolean collectorSent;
        private boolean collected;

        public Party(String code, boolean sending) {
            this.code = code;
            this.sending = sending;
        }

        public String code() {
            return code;
        }

        public boolean sending() {
            return sending;
        }

        public boolean dispatched() {
            return dispatched;
        }

        public void markDispatched() {
            this.dispatched = true;
        }

        @Nullable
        public UUID droppedCrate() {
            return droppedCrate;
        }

        public void markDropped(UUID crate) {
            this.droppedCrate = crate;
        }

        public boolean collectorSent() {
            return collectorSent;
        }

        public void markCollectorSent() {
            this.collectorSent = true;
        }

        public boolean collected() {
            return collected;
        }

        public void markCollected() {
            this.collected = true;
        }

        /**
         * @param other the party on the far side
         * @return true when this side has delivered whatever it owed and picked up whatever was left for it.
         */
        public boolean settled(Party other) {
            boolean deliveredOwn = !sending || droppedCrate != null;
            boolean tookTheirs = !other.sending || collected;
            return deliveredOwn && tookTheirs;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Code", code);
            tag.putBoolean("Sending", sending);
            tag.putBoolean("Dispatched", dispatched);
            if (droppedCrate != null) {
                tag.putUUID("Crate", droppedCrate);
            }
            tag.putBoolean("CollectorSent", collectorSent);
            tag.putBoolean("Collected", collected);
            return tag;
        }

        public static Party load(CompoundTag tag) {
            Party party = new Party(tag.getString("Code"), tag.getBoolean("Sending"));
            party.dispatched = tag.getBoolean("Dispatched");
            party.droppedCrate = tag.hasUUID("Crate") ? tag.getUUID("Crate") : null;
            party.collectorSent = tag.getBoolean("CollectorSent");
            party.collected = tag.getBoolean("Collected");
            return party;
        }
    }
}
