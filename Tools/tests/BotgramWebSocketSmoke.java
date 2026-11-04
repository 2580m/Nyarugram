package com.neovisionaries.ws.client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;

public class BotgramWebSocketSmoke {
    private static int checks;
    private static final int DEFAULT_BUDGET = 8 * 1024 * 1024;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    // Fail rather than returning EOF if the parser touches an absent attack body.
    private static class HeaderOnly extends InputStream {
        private final byte[] bytes;
        private int position;
        HeaderOnly(byte[] bytes) { this.bytes = bytes; }
        public int read() {
            if (position == bytes.length) throw new AssertionError("Read beyond header");
            return bytes[position++] & 255;
        }
        public long skip(long count) { throw new AssertionError("Skipped attack body"); }
    }

    private static byte[] header(int first, long length, int encoding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(first);
        if (encoding == 127) { data.writeByte(127); data.writeLong(length); }
        else if (encoding == 126) { data.writeByte(126); data.writeShort((int) length); }
        else data.writeByte((int) length);
        return bytes.toByteArray();
    }

    private static byte[] frame(int first, int length) throws IOException {
        return concat(header(first, length, length > 125 ? (length > 65535 ? 127 : 126) : 0), new byte[length]);
    }

    private static byte[] concat(byte[]... inputs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] input : inputs) out.write(input);
        return out.toByteArray();
    }

    private static void reject(WebSocketInputStream input, WebSocketError expected, String name) throws Exception {
        try { input.readFrame(); throw new AssertionError(name + ": accepted"); }
        catch (WebSocketException e) { check(e.getError() == expected, name + ": " + e.getError()); }
        // ReadingThread.waitForCloseFrame must not retry reading the attack body.
        try { input.readFrame(); throw new AssertionError(name + ": retried"); }
        catch (WebSocketException e) { check(e.getError() == expected, name + ": terminal failure"); }
    }

    private static void rejectHeader(int first, long length, int encoding, WebSocketError error) throws Exception {
        reject(new WebSocketInputStream(new HeaderOnly(header(first, length, encoding))), error,
            "header " + first + "/" + length);
    }

    private static void parserTests() throws Exception {
        WebSocketInputStream bounded = new WebSocketInputStream(new HeaderOnly(header(0x82, 1025, 126)));
        bounded.setReceiveLimit(1024);
        reject(bounded, WebSocketError.TOO_LONG_PAYLOAD, "protobuf single-frame header budget");
        bounded = new WebSocketInputStream(new HeaderOnly(concat(frame(0x02, 1024), header(0x80, 1, 0))));
        bounded.setReceiveLimit(1024);
        bounded.readFrame();
        reject(bounded, WebSocketError.TOO_LONG_PAYLOAD, "protobuf aggregate budget");
        bounded = new WebSocketInputStream(new ByteArrayInputStream(concat(frame(0x02, 1023), frame(0x80, 1))));
        bounded.setReceiveLimit(1024);
        check(bounded.readFrame().getPayloadLength() == 1023, "protobuf boundary first fragment");
        check(bounded.readFrame().getPayloadLength() == 1, "protobuf exact 1024-byte aggregate");
        bounded = new WebSocketInputStream(new ByteArrayInputStream(frame(0x82, 65536)));
        check(bounded.readFrame().getPayloadLength() == 65536, "64-bit valid length parsed exactly");
        rejectHeader(0x82, DEFAULT_BUDGET + 1L, 127, WebSocketError.TOO_LONG_PAYLOAD);
        for (long length : new long[] { 0x100000000L, 0x100000001L, 0x10000000000L, Long.MAX_VALUE })
            rejectHeader(0x82, length, 127, WebSocketError.TOO_LONG_PAYLOAD);
        rejectHeader(0x82, Long.MIN_VALUE, 127, WebSocketError.INVALID_PAYLOAD_LENGTH);
        rejectHeader(0x89, 126, 126, WebSocketError.TOO_LONG_CONTROL_FRAME_PAYLOAD);
        rejectHeader(0x09, 0, 0, WebSocketError.FRAGMENTED_CONTROL_FRAME);
        rejectHeader(0xC2, 0, 0, WebSocketError.UNEXPECTED_RESERVED_BIT);
        rejectHeader(0xA2, 0, 0, WebSocketError.UNEXPECTED_RESERVED_BIT);
        rejectHeader(0x92, 0, 0, WebSocketError.UNEXPECTED_RESERVED_BIT);
        rejectHeader(0x83, 0, 0, WebSocketError.UNKNOWN_OPCODE);
        rejectHeader(0x8B, 0, 0, WebSocketError.UNKNOWN_OPCODE);
        rejectHeader(0x80, 0, 0, WebSocketError.UNEXPECTED_CONTINUATION_FRAME);
        reject(new WebSocketInputStream(new HeaderOnly(new byte[] { (byte)0x82, (byte)0x80 })),
            WebSocketError.FRAME_MASKED, "masked without key/body");

        WebSocketInputStream input = new WebSocketInputStream(new ByteArrayInputStream(concat(
            frame(0x82, DEFAULT_BUDGET), frame(0x82, DEFAULT_BUDGET))));
        check(input.readFrame().getPayloadLength() == DEFAULT_BUDGET, "exact single-frame budget");
        check(input.readFrame().getPayloadLength() == DEFAULT_BUDGET, "new message resets budget");
        input = new WebSocketInputStream(new ByteArrayInputStream(concat(
            frame(0x02, DEFAULT_BUDGET - 1), frame(0x89, 125), frame(0x8A, 0), frame(0x80, 1))));
        check(input.readFrame().getPayloadLength() == DEFAULT_BUDGET - 1, "fragment payload");
        check(input.readFrame().isPingFrame(), "interleaved ping");
        check(input.readFrame().isPongFrame(), "interleaved pong");
        check(input.readFrame().getPayloadLength() == 1, "exact fragmented budget");

        input = new WebSocketInputStream(new HeaderOnly(concat(
            frame(0x02, DEFAULT_BUDGET), frame(0x89, 0), header(0x80, 1, 0))));
        input.readFrame(); input.readFrame();
        reject(input, WebSocketError.TOO_LONG_PAYLOAD, "ping does not reset message bytes");
        input = new WebSocketInputStream(new HeaderOnly(concat(frame(0x02, 0), header(0x82, 0, 0))));
        input.readFrame();
        reject(input, WebSocketError.CONTINUATION_NOT_CLOSED, "second start");

        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        fragments.write(frame(0x02, 0));
        for (int i = 1; i < 1024; ++i) fragments.write(frame(0x00, 0));
        fragments.write(frame(0x89, 0));
        fragments.write(header(0x80, 0, 0));
        input = new WebSocketInputStream(new HeaderOnly(fragments.toByteArray()));
        for (int i = 0; i < 1025; ++i) input.readFrame();
        reject(input, WebSocketError.TOO_LONG_PAYLOAD, "1025th empty fragment, ping does not reset count");
        fragments.reset(); fragments.write(frame(0x02, 0));
        for (int i = 1; i < 1023; ++i) fragments.write(frame(0x00, 0));
        fragments.write(frame(0x80, 0)); fragments.write(frame(0x82, 0));
        input = new WebSocketInputStream(new ByteArrayInputStream(fragments.toByteArray()));
        for (int i = 0; i < 1025; ++i) input.readFrame();
        check(true, "1024 empty fragments allowed and final resets count");
        for (int opcode : new int[] { 8, 9, 10 }) {
            input = new WebSocketInputStream(new ByteArrayInputStream(frame(0x80 | opcode, 125)));
            check(input.readFrame().getPayloadLength() == 125, "control limit " + opcode);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private static void archiveTest(String upstream, String patched) throws Exception {
        try (JarFile original = new JarFile(upstream); JarFile result = new JarFile(patched)) {
            int changed = 0;
            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                JarEntry replacement = result.getJarEntry(entry.getName());
                if (entry.getName().equals("com/neovisionaries/ws/client/WebSocketInputStream.class")) {
                    check(replacement == null, "upstream reader must be excluded");
                    ++changed;
                    continue;
                }
                check(replacement != null, "missing jar entry " + entry.getName());
                try (InputStream a = original.getInputStream(entry); InputStream b = result.getInputStream(replacement)) {
                    check(Arrays.equals(readAll(a), readAll(b)), "unchanged upstream entry " + entry.getName());
                }
            }
            check(original.size() == result.size() + 1 && changed == 1, "exactly one class excluded");
        }
    }

    private static void loopbackTest() throws Exception {
        CountDownLatch rejected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> peer = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line, key = null;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
                        if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-extensions:"))
                            throw new AssertionError("Compression must not be offered");
                    }
                    if (key == null) throw new AssertionError("No handshake key");
                    String accept = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\nSec-WebSocket-Protocol: botgram.event-hint.v1.protobuf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    // Send the header immediately with the handshake, without waiting
                    // for any listener: OPEN must configure the budget before reads begin.
                    // No body and no peer close: ReadingThread must fail and close promptly.
                    out.write(header(0x82, 1025, 126)); out.flush();
                    InputStream in = socket.getInputStream();
                    while (in.read() != -1) { }
                } catch (Throwable t) { throw new RuntimeException(t); }
            });
            WebSocket ws = new WebSocketFactory().setConnectionTimeout(2000)
                .createSocket("ws://127.0.0.1:" + server.getLocalPort() + "/");
            ws.addProtocol("botgram.event-hint.v1.protobuf");
            ws.addListener(new WebSocketAdapter() {
                public void onStateChanged(WebSocket socket, WebSocketState state) {
                    // OPEN is synchronous after the handshake, before ReadingThread starts.
                    if (state == WebSocketState.OPEN) {
                        try { BotgramReceiveLimits.configure(socket); }
                        catch (Throwable t) { failure.compareAndSet(null, t); }
                    }
                }
                public void onFrameError(WebSocket socket, WebSocketException cause, WebSocketFrame frame) {
                    if (cause.getError() != WebSocketError.TOO_LONG_PAYLOAD)
                        failure.compareAndSet(null, new AssertionError("Unexpected error " + cause.getError()));
                    rejected.countDown();
                }
                public void onBinaryMessage(WebSocket socket, byte[] bytes) {
                    failure.compareAndSet(null, new AssertionError("Oversized binary message delivered"));
                }
                public void onDisconnected(WebSocket socket, WebSocketFrame serverClose, WebSocketFrame clientClose, boolean byServer) {
                    disconnected.countDown();
                }
            });
            try {
                ws.connect();
                check(rejected.await(2, TimeUnit.SECONDS), "real ReadingThread rejects header immediately");
                check(disconnected.await(2, TimeUnit.SECONDS), "real ReadingThread disconnects without body/peer close");
                peer.get(5, TimeUnit.SECONDS);
                if (failure.get() != null) throw new AssertionError("Loopback listener failure", failure.get());
                check(true, "post-handshake configure(1024) and actual loopback close");
            } finally {
                ws.disconnect(); executor.shutdownNow();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        archiveTest(args[0], args[1]);
        parserTests();
        loopbackTest();
        System.out.println("PASS: " + checks + " checks; real nv readFrame and loopback ReadingThread");
    }
}
