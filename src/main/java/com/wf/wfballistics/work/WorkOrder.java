package com.wf.wfballistics.work;

import net.minecraft.core.BlockPos;

/**
 * One indivisible unit of work: somewhere to be, and one small integer saying what to do there.
 *
 * <p>Deliberately flat, and deliberately without a payload of its own. A {@link WorkQueue} holding tens of
 * thousands of these has to serialise in one pass and be scanned every time a worker asks for something to
 * do, so an order is four numbers and nothing else: see {@code WorkQueue#save}, which writes the whole queue
 * as three primitive arrays rather than a list of compounds.
 *
 * @param id       index into the queue that owns this order, and its identity. Stable for the life of the
 *                 queue, which is what lets a claim be a single integer
 * @param at       where the work happens
 * @param sequence the ordering key, and the only thing the queue knows about dependencies. Orders are handed
 *                 out lowest-sequence-first, and never more than {@code WorkQueue#LOOKAHEAD} beyond the
 *                 lowest one still outstanding. What that <em>means</em> is up to whoever built the queue:
 *                 a build encodes the layer, a demolition encodes the layer inverted. See
 *                 {@link WorkQueue#claim} for why the gate exists at all
 * @param data     one domain-specific integer: a palette index for a build, unused for a salvage. Anything
 *                 needing more than an int keeps a side table and indexes it with this
 */
public record WorkOrder(int id, BlockPos at, int sequence, int data) {
}
