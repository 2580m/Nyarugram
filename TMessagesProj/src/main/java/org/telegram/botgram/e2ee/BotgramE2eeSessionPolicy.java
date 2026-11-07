package org.telegram.botgram.e2ee;

/**
 * Pure decision logic for session reuse and identity replacement. Kept free of Android
 * dependencies so the real confirmation sequence can be regressed on a host JVM.
 */
public final class BotgramE2eeSessionPolicy {
    public enum Decision {
        /** Existing session matches the trusted identity and generation. */
        REUSE,
        /** No session yet, or a just-confirmed replacement may replace the old one. */
        ESTABLISH,
        /** The peer identity or generation changed and needs explicit confirmation. */
        REQUIRE_CONFIRMATION
    }

    public static final class Replacement {
        public final byte[] identity;
        public final int generation;
        public Replacement(byte[] identity, int generation) {
            this.identity = identity;
            this.generation = generation;
        }
    }

    private BotgramE2eeSessionPolicy() {}

    /** The first confirmer receives SUSPENDED; that confirmation is just as durable as ACTIVE. */
    public static boolean acceptsConfirmation(Replacement displayed,
            org.telegram.botgram.protocol.v1.BotgramRpc.E2eeChatStatus response) {
        return displayed != null && response.getSelfConfirmedIdentity()
                && (response.getState() == org.telegram.botgram.protocol.v1.BotgramRpc.E2eeState.E2EE_ACTIVE
                || response.getState() == org.telegram.botgram.protocol.v1.BotgramRpc.E2eeState.E2EE_SUSPENDED)
                && displayed.generation == response.getPeerGeneration()
                && java.util.Arrays.equals(displayed.identity, response.getPeerIdentityKey().toByteArray());
    }

    public static Decision decide(boolean hasSession, byte[] trustedIdentity, int trustedGeneration,
            byte[] peerIdentity, int peerGeneration, Replacement confirmed) {
        boolean identityChanged = trustedIdentity != null && peerIdentity != null
                && !java.util.Arrays.equals(trustedIdentity, peerIdentity);
        boolean generationChanged = trustedGeneration > 0 && peerGeneration > 0
                && trustedGeneration != peerGeneration;
        boolean confirmedMatches = confirmed != null && peerIdentity != null
                && confirmed.generation == peerGeneration
                && java.util.Arrays.equals(confirmed.identity, peerIdentity);
        if (identityChanged || generationChanged) {
            // A confirmation for an earlier key must not cover a later replacement.
            return confirmedMatches ? Decision.ESTABLISH : Decision.REQUIRE_CONFIRMATION;
        }
        if (hasSession) {
            if (trustedIdentity == null) return Decision.REQUIRE_CONFIRMATION;
            return Decision.REUSE;
        }
        return Decision.ESTABLISH;
    }
}
