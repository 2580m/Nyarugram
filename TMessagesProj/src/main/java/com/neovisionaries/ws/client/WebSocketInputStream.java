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
 *
 * Modified for Botgram: bound frame allocation and fragmented messages before
 * reading payloads, validate headers without extensions, and fix long shifts.
 */
package com.neovisionaries.ws.client;


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;


class WebSocketInputStream extends FilterInputStream
{
    private volatile int mReceiveLimit = 8 * 1024 * 1024;
    private WebSocketException mFailure;
    private long mMessageBytes;
    private int mFragmentCount;
    private static final int MAX_FRAGMENTS = 1024;


    public WebSocketInputStream(InputStream in)
    {
        super(in);
    }


    void setReceiveLimit(int limit)
    {
        if (limit <= 0 || limit > 8 * 1024 * 1024)
        {
            throw new IllegalArgumentException("Receive limit must be between 1 and 8 MiB");
        }
        mReceiveLimit = limit;
    }


    public String readLine() throws IOException
    {
        return Misc.readLine(this, "UTF-8");
    }


    public WebSocketFrame readFrame() throws IOException, WebSocketException
    {
        // ReadingThread retries here while waiting for close. An unconsumed attack
        // body must never be treated as another header or block that retry.
        if (mFailure != null)
        {
            throw mFailure;
        }
        try
        {
            return readFrameOnce();
        }
        catch (WebSocketException e)
        {
            mFailure = e;
            throw e;
        }
    }


    private WebSocketFrame readFrameOnce() throws IOException, WebSocketException
    {
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
                throw new NoMoreFrameException();
            }
            else
            {
                throw e;
            }
        }

        boolean fin = ((buffer[0] & 0x80) != 0);
        boolean rsv1 = ((buffer[0] & 0x40) != 0);
        boolean rsv2 = ((buffer[0] & 0x20) != 0);
        boolean rsv3 = ((buffer[0] & 0x10) != 0);
        int opcode = (buffer[0] & 0x0F);
        boolean mask = ((buffer[1] & 0x80) != 0);
        long payloadLength = buffer[1] & 0x7F;

        if (payloadLength == 126)
        {
            readBytes(buffer, 2);
            payloadLength = (((buffer[0] & 0xFF) << 8) |
                             ((buffer[1] & 0xFF)     ));
        }
        else if (payloadLength == 127)
        {
            readBytes(buffer, 8);
            if ((buffer[0] & 0x80) != 0)
            {
                throw new WebSocketException(
                    WebSocketError.INVALID_PAYLOAD_LENGTH,
                    "The payload length of a frame is invalid.");
            }

            payloadLength = (((buffer[0] & 0xFFL) << 56) |
                             ((buffer[1] & 0xFFL) << 48) |
                             ((buffer[2] & 0xFFL) << 40) |
                             ((buffer[3] & 0xFFL) << 32) |
                             ((buffer[4] & 0xFFL) << 24) |
                             ((buffer[5] & 0xFFL) << 16) |
                             ((buffer[6] & 0xFFL) <<  8) |
                             ((buffer[7] & 0xFFL)      ));
        }

        // Botgram negotiates no extensions. Reject invalid headers and budgets
        // before allocating, reading a masking key, or waiting for any body bytes.
        validateHeader(fin, rsv1 || rsv2 || rsv3, opcode, mask, payloadLength);
        byte[] payload = readPayload(payloadLength);

        return new WebSocketFrame()
            .setFin(fin)
            .setRsv1(rsv1)
            .setRsv2(rsv2)
            .setRsv3(rsv3)
            .setOpcode(opcode)
            .setMask(mask)
            .setPayload(payload);
    }


    private void validateHeader(boolean fin, boolean reserved, int opcode, boolean mask,
        long length) throws WebSocketException
    {
        if (reserved)
        {
            throw new WebSocketException(WebSocketError.UNEXPECTED_RESERVED_BIT, "Extensions are not enabled.");
        }
        if (mask)
        {
            throw new WebSocketException(WebSocketError.FRAME_MASKED, "Server frames must not be masked.");
        }
        if (opcode != 0 && opcode != 1 && opcode != 2 && opcode != 8 && opcode != 9 && opcode != 10)
        {
            throw new WebSocketException(WebSocketError.UNKNOWN_OPCODE, "Unknown frame opcode.");
        }
        if (opcode >= 8)
        {
            if (!fin)
            {
                throw new WebSocketException(WebSocketError.FRAGMENTED_CONTROL_FRAME, "Control frames must be final.");
            }
            if (length > 125)
            {
                throw new WebSocketException(WebSocketError.TOO_LONG_CONTROL_FRAME_PAYLOAD, "Control payload exceeds 125 bytes.");
            }
            return;
        }
        if (opcode == 0 && mFragmentCount == 0)
        {
            throw new WebSocketException(WebSocketError.UNEXPECTED_CONTINUATION_FRAME, "No fragmented message is open.");
        }
        if (opcode != 0 && mFragmentCount != 0)
        {
            throw new WebSocketException(WebSocketError.CONTINUATION_NOT_CLOSED, "A fragmented message is already open.");
        }

        int limit = mReceiveLimit;
        if (length > limit || mMessageBytes > limit - length || mFragmentCount >= MAX_FRAGMENTS)
        {
            throw new WebSocketException(WebSocketError.TOO_LONG_PAYLOAD, "Receive message budget exceeded.");
        }
        if (fin)
        {
            mMessageBytes = 0;
            mFragmentCount = 0;
        }
        else
        {
            mMessageBytes += length;
            ++mFragmentCount;
        }
    }


    void readBytes(byte[] buffer, int length) throws IOException, WebSocketException
    {
        int total = 0;
        while (total < length)
        {
            int count = read(buffer, total, length - total);
            if (count <= 0)
            {
                throw new InsufficientDataException(length, total);
            }
            total += count;
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
            payload = new byte[(int)payloadLength];
        }
        catch (OutOfMemoryError e)
        {
            throw new WebSocketException(
                WebSocketError.INSUFFICIENT_MEMORY_FOR_PAYLOAD,
                "OutOfMemoryError occurred during a trial to allocate memory for a frame payload: " + e.getMessage(), e);
        }

        readBytes(payload, payload.length);
        return payload;
    }
}
