package fr.buildtheearth.hypso.pipeline;

/**
 * Decides which height frame a client packet was written in when it was held across a window switch.
 *
 * <p>Between the flip of the offset and the moment the client adopts the new frame, packets the client
 * sent may still be in the old frame. Each held packet therefore has two candidate real-space positions:
 * one under the previous offset and one under the current offset. The candidates differ by the switch
 * delta. A candidate is accepted only if it is physically plausible relative to the last known real Y (the
 * anchor). The result is a unique frame, or {@link Frame#NONE}, in which case the packet is dropped. A
 * wrong frame would teleport the player to the wrong altitude, which is worse than losing one packet.</p>
 *
 * <p>Plausibility is a bound on displacement, not a proof. A switch with a small delta (for example a
 * clamp at the world edge) can make both candidates plausible, and the resolver then drops the packet.</p>
 */
final class FrameResolver {

    enum Frame {
        /** The packet was written under the offset in force before the switch. */
        PREVIOUS,
        /** The packet was written under the offset in force after the switch. */
        CURRENT,
        /** Neither or both candidates are plausible: the packet must be dropped. */
        NONE
    }

    /** Largest real-space displacement assumed for a freeze of zero length, in blocks. */
    static final double BASE_TRAVEL_BLOCKS = 24.0;
    /** Generous upper bound on player speed (elytra with boosts, fast falls), in blocks per second. */
    static final double TRAVEL_BLOCKS_PER_SECOND = 60.0;
    /** Bedrock interaction reach, in blocks, measured from the player's feet to the block centre. */
    static final double REACH_BLOCKS = 7.0;

    private FrameResolver() {
    }

    /** Maximum displacement allowed between the anchor and a held movement, given the elapsed freeze time. */
    static double travelBound(long elapsedNanos) {
        return BASE_TRAVEL_BLOCKS + TRAVEL_BLOCKS_PER_SECOND * Math.max(0L, elapsedNanos) / 1.0e9;
    }

    /**
     * Frame of a held movement packet.
     *
     * @param anchorRealY last known real Y of the player
     * @param clientY     the Y value carried by the packet
     * @param previous    offset before the switch
     * @param current     offset after the switch
     * @param bound       plausibility bound from {@link #travelBound(long)}
     */
    static Frame movement(double anchorRealY, double clientY, int previous, int current, double bound) {
        return classify(anchorRealY, clientY + previous, clientY + current, bound);
    }

    /**
     * Frame of a held block interaction.
     *
     * @param anchorRealY last known real Y of the player (feet)
     * @param clientBlockY block Y carried by the packet
     */
    static Frame blockAction(double anchorRealY, int clientBlockY, int previous, int current) {
        return classify(anchorRealY,
            clientBlockY + previous + 0.5, clientBlockY + current + 0.5, REACH_BLOCKS);
    }

    private static Frame classify(double anchorRealY, double candidatePrevious, double candidateCurrent, double bound) {
        boolean previousPlausible = Math.abs(candidatePrevious - anchorRealY) <= bound;
        boolean currentPlausible = Math.abs(candidateCurrent - anchorRealY) <= bound;
        if (previousPlausible == currentPlausible) {
            return Frame.NONE;
        }
        return previousPlausible ? Frame.PREVIOUS : Frame.CURRENT;
    }
}
