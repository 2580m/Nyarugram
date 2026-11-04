package com.neovisionaries.ws.client;

/** Receive-side limits for Botgram's extension-free event connection. */
public final class BotgramReceiveLimits {
    private BotgramReceiveLimits() { }

    public static void configure(WebSocket socket) {
        socket.getInput().setReceiveLimit(
                "botgram.event-hint.v1.protobuf".equals(socket.getAgreedProtocol()) ? 1024 : 8 * 1024 * 1024);
    }
}
