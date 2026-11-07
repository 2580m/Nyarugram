package org.telegram.botgram.e2ee;

import org.telegram.botgram.protocol.v1.BotgramRpc;

/** The local text carrier for an encrypted message. Plaintext never uses this prefix. */
public final class BotgramE2eeEnvelopeCodec {
    public static final String PREFIX = "\u0001BGE1:";

    private BotgramE2eeEnvelopeCodec() {}

    public static boolean isEnvelope(CharSequence text) {
        return text != null && text.length() > PREFIX.length() && text.toString().startsWith(PREFIX);
    }

    public static String wrap(BotgramRpc.E2eeEnvelope envelope) {
        return PREFIX + BotgramE2eeBase64.encode(envelope.toByteArray());
    }

    public static byte[] unwrapBytes(String text) throws BotgramE2eeCrypto.BotgramE2eeException {
        if (!isEnvelope(text)) throw new BotgramE2eeCrypto.BotgramE2eeException("E2EE_NOT_ENVELOPE");
        try {
            return BotgramE2eeBase64.decode(text.substring(PREFIX.length()));
        } catch (IllegalArgumentException error) {
            throw new BotgramE2eeCrypto.BotgramE2eeException("E2EE_ENVELOPE_INVALID");
        }
    }

    public static BotgramRpc.E2eeEnvelope unwrap(String text) throws BotgramE2eeCrypto.BotgramE2eeException {
        try {
            return BotgramRpc.E2eeEnvelope.parseFrom(unwrapBytes(text));
        } catch (BotgramE2eeCrypto.BotgramE2eeException error) {
            throw error;
        } catch (Exception error) {
            throw new BotgramE2eeCrypto.BotgramE2eeException("E2EE_ENVELOPE_INVALID");
        }
    }
}

