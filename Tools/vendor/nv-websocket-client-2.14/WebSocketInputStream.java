/*
 * Copyright (C) 2015-2016 Neo Visionaries Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neovisionaries.ws.client;


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;


public class WebSocketInputStream extends FilterInputStream
{
    private volatile int mReceiveBudget = 8 * 1024 * 1024;
    private long mMessageBytes;
    private int mMessageFragments;
    private WebSocketException mFailure;


    public static void configure(WebSocket webSocket, int receiveBudget)
    {
        if (receiveBudget <= 0)
        {
            throw new IllegalArgumentException("The receive budget must be positive.");
        }

        WebSocketInputStream input = webSocket.getInput();
        if (input == null)
        {
            throw new IllegalStateException("The WebSocket handshake has not completed.");
        }
        input.mReceiveBudget = receiveBudget;
    }

    public WebSocketInputStream(InputStream in)
    {
        super(in);
    }


    public String readLine() throws IOException
    {
        return Misc.readLine(this, "UTF-8");
    }


    public WebSocketFrame readFrame() throws IOException, WebSocketException
    {
        // A rejected header is terminal: ReadingThread must not drain its body
        // while waiting for the peer's close frame.
        if (mFailure != null)
        {
            throw mFailure;
        }

        // Buffer.
        byte[] buffer = new byte[8];

        try
        {
            // Read the first two bytes.
            readBytes(buffer, 2);
        }
        catch (InsufficientDataException e)
        {
            if (e.getReadByteCount() == 0)
            {
                // The connection has been closed without receiving a close frame.
                // Strictly speaking, this is a violation against RFC 6455.
                throw new NoMoreFrameException();
            }
            else
            {
                // Re-throw the exception.
                throw e;
            }
        }

        // FIN
        boolean fin = ((buffer[0] & 0x80) != 0);

        // RSV1, RSV2, RSV3
        boolean rsv1 = ((buffer[0] & 0x40) != 0);
        boolean rsv2 = ((buffer[0] & 0x20) != 0);
        boolean rsv3 = ((buffer[0] & 0x10) != 0);

        // Opcode
        int opcode = (buffer[0] & 0x0F);

        // Mask flag. This should never be true because the specification
        // (RFC 6455, 5. Data Framing, 5.1. Overview) says as follows:
        //
        //     A server MUST NOT mask any frames that it sends to the client.
        //
        boolean mask = ((buffer[1] & 0x80) != 0);

        // The payload length. It is expressed in 7 bits.
        long payloadLength = buffer[1] & 0x7F;

        if (payloadLength == 126)
        {
            // Read the extended payload length.
            // It is expressed in 2 bytes in network byte order.
            readBytes(buffer, 2);

            // Interpret the bytes as a number.
            payloadLength = (((buffer[0] & 0xFF) << 8) |
                             ((buffer[1] & 0xFF)     ));
        }
        else if (payloadLength == 127)
        {
            // Read the extended payload length.
            // It is expressed in 8 bytes in network byte order.
            readBytes(buffer, 8);

            // From RFC 6455, p29.
            //
            //   the most significant bit MUST be 0
            //
            if ((buffer[0] & 0x80) != 0)
            {
                // The payload length in a frame is invalid.
                throw fail(
                    WebSocketError.INVALID_PAYLOAD_LENGTH,
                    "The payload length of a frame is invalid.");
            }

            // Interpret the bytes as a number.
            payloadLength = 0;
            for (int i = 0; i < 8; ++i)
            {
                payloadLength = (payloadLength << 8) | (buffer[i] & 0xFFL);
            }
        }

        validateHeader(fin, rsv1 || rsv2 || rsv3, opcode, mask, payloadLength);

        // Read only after validating the complete header and message budget.
        byte[] payload = readPayload(payloadLength);

        // Create a WebSocketFrame instance that represents a frame.
        return new WebSocketFrame()
            .setFin(fin)
            .setRsv1(rsv1)
            .setRsv2(rsv2)
            .setRsv3(rsv3)
            .setOpcode(opcode)
            .setMask(mask)
            .setPayload(payload);
    }


    void readBytes(byte[] buffer, int length) throws IOException, WebSocketException
    {
        // Read
        int total = 0;

        while (total < length)
        {
            int count = read(buffer, total, length - total);

            if (count <= 0)
            {
                // The end of the stream has been reached unexpectedly.
                throw new InsufficientDataException(length, total);
            }

            total += count;
        }
    }


    private WebSocketException fail(WebSocketError error, String message)
    {
        mFailure = new WebSocketException(error, message);
        return mFailure;
    }


    private void validateHeader(boolean fin, boolean reserved, int opcode,
        boolean mask, long payloadLength) throws WebSocketException
    {
        if (reserved)
        {
            throw fail(WebSocketError.UNEXPECTED_RESERVED_BIT, "Extensions are not supported.");
        }
        if (mask)
        {
            throw fail(WebSocketError.FRAME_MASKED, "A server frame must not be masked.");
        }
        if (opcode != 0 && opcode != 1 && opcode != 2 &&
            opcode != 8 && opcode != 9 && opcode != 10)
        {
            throw fail(WebSocketError.UNKNOWN_OPCODE, "Unknown frame opcode.");
        }
        if (opcode >= 8)
        {
            if (!fin)
            {
                throw fail(WebSocketError.FRAGMENTED_CONTROL_FRAME, "A control frame must be final.");
            }
            if (payloadLength > 125)
            {
                throw fail(WebSocketError.TOO_LONG_CONTROL_FRAME_PAYLOAD, "A control frame exceeds 125 bytes.");
            }
            return;
        }
        if (opcode == 0 && mMessageFragments == 0)
        {
            throw fail(WebSocketError.UNEXPECTED_CONTINUATION_FRAME, "No fragmented message is open.");
        }
        if (opcode != 0 && mMessageFragments != 0)
        {
            throw fail(WebSocketError.CONTINUATION_NOT_CLOSED, "A fragmented message is still open.");
        }
        int budget = mReceiveBudget;
        if (payloadLength > budget || mMessageBytes > budget - payloadLength)
        {
            throw fail(WebSocketError.TOO_LONG_PAYLOAD, "The message exceeds the receive budget.");
        }
        if (mMessageFragments >= 1024)
        {
            throw fail(WebSocketError.TOO_LONG_PAYLOAD, "The message exceeds 1024 fragments.");
        }
        mMessageBytes += payloadLength;
        ++mMessageFragments;
        if (fin)
        {
            mMessageBytes = 0;
            mMessageFragments = 0;
        }
    }


    private byte[] readPayload(long payloadLength) throws IOException, WebSocketException
    {
        if (payloadLength == 0)
        {
            return null;
        }

        byte[] payload;

        try
        {
            // Allocate a memory area to hold the content of the payload.
            payload = new byte[(int)payloadLength];
        }
        catch (OutOfMemoryError e)
        {
            // Do not drain an unread payload after allocation failure.
            mFailure = new WebSocketException(WebSocketError.INSUFFICIENT_MEMORY_FOR_PAYLOAD,
                "Could not allocate the frame payload.");
            throw new WebSocketException(
                WebSocketError.INSUFFICIENT_MEMORY_FOR_PAYLOAD,
                "OutOfMemoryError occurred during a trial to allocate a memory area for a frame's payload: " + e.getMessage(), e);
        }

        // Read the payload.
        readBytes(payload, payload.length);

        return payload;
    }
}
