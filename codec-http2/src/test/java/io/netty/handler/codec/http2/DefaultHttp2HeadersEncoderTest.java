/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.util.AsciiString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static io.netty.handler.codec.http2.Http2TestUtil.newTestEncoder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultHttp2HeadersEncoder}.
 */
public class DefaultHttp2HeadersEncoderTest {

    private DefaultHttp2HeadersEncoder encoder;

    @BeforeEach
    public void setup() {
        encoder = new DefaultHttp2HeadersEncoder(Http2HeadersEncoder.NEVER_SENSITIVE, newTestEncoder());
    }

    @AfterEach
    public void tearDown() {
        encoder.close();
    }

    @Test
    public void encodeShouldSucceed() throws Http2Exception {
        Http2Headers headers = headers();
        ByteBuf buf = Unpooled.buffer();
        try {
            encoder.encodeHeaders(3 /* randomly chosen */, headers, buf);
            assertTrue(buf.writerIndex() > 0);
        } finally {
            buf.release();
        }
    }

    @Test
    public void headersExceedMaxSetSizeShouldFail() throws Http2Exception {
        final Http2Headers headers = headers();
        encoder.maxHeaderListSize(2);
        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                ByteBuf buf = Unpooled.buffer();
                try {
                    encoder.encodeHeaders(3 /* randomly chosen */, headers, buf);
                } finally {
                    buf.release();
                }
            }
        });
    }

    @Test
    public void publicEncoderRetainsNearlySixtyFourKiB() throws Http2Exception {
        assertPublicEncoderRetainsNearlySixtyFourKiB(false);
    }

    @Test
    public void publicEncoderRetainsNearlySixtyFourKiBOfDistinctNames() throws Http2Exception {
        assertPublicEncoderRetainsNearlySixtyFourKiB(true);
    }

    private static void assertPublicEncoderRetainsNearlySixtyFourKiB(boolean distinctNames) throws Http2Exception {
        // Exercise the public default constructor, not a specially sized internal encoder.
        DefaultHttp2HeadersEncoder publicEncoder = new DefaultHttp2HeadersEncoder();
        DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
        ByteBuf buf = Unpooled.buffer();
        try {
            publicEncoder.configuration().maxHeaderTableSize(65536);
            decoder.configuration().maxHeaderTableSize(65536);
            assertEquals(65536, publicEncoder.configuration().maxHeaderTableSize());
            Http2Headers[] retained = new Http2Headers[1120];
            java.util.Random random = new java.util.Random(194);
            long retainedSize = 0;
            for (int i = 0; i < retained.length; ++i) {
                String token = Long.toHexString(random.nextLong());
                String name = distinctNames ? "x-field-" + token : "x-retained";
                String value = distinctNames ? "v1" : token;
                retained[i] = new DefaultHttp2Headers().add(name, value);
                retainedSize += 32 + name.length() + value.length();
                publicEncoder.encodeHeaders(3, retained[i], buf);
                assertEquals(retained[i], decoder.decodeHeaders(3, buf));
                buf.clear();
            }
            assertTrue(retainedSize > 64000 && retainedSize <= 65536);
            // A round trip alone could pass with a silently capped 4 KiB table. Require every
            // retained entry to be sent as an index, oldest first, without reinsertion.
            for (Http2Headers headers : retained) {
                publicEncoder.encodeHeaders(3, headers, buf);
                assertEquals(0x80, buf.getUnsignedByte(0) & 0x80);
                assertTrue(buf.readableBytes() <= 3);
                assertEquals(headers, decoder.decodeHeaders(3, buf));
                buf.clear();
            }
        } finally {
            publicEncoder.close();
            buf.release();
        }
    }

    @Test
    public void publicEncoderRetainsBrowserPathsNearSixtyFourKiB() throws Http2Exception {
        DefaultHttp2HeadersEncoder publicEncoder = new DefaultHttp2HeadersEncoder();
        DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
        ByteBuf buf = Unpooled.buffer();
        try {
            publicEncoder.configuration().maxHeaderTableSize(65536);
            decoder.configuration().maxHeaderTableSize(65536);
            Http2Headers[] requests = new Http2Headers[1020];
            java.util.Random random = new java.util.Random(194);
            long retainedSize = 32 + ":authority".length() + "example.com".length()
                    + 32 + "cookie".length() + "session=stable".length();
            for (int i = 0; i < requests.length; ++i) {
                String path = "/api/items/" + Long.toHexString(random.nextLong());
                retainedSize += 32 + ":path".length() + path.length();
                requests[i] = new DefaultHttp2Headers().method("GET").scheme("https")
                        .authority("example.com").path(path).add("cookie", "session=stable");
                publicEncoder.encodeHeaders(3, requests[i], buf);
                assertEquals(requests[i], decoder.decodeHeaders(3, buf));
                buf.clear();
            }
            assertTrue(retainedSize > 64000 && retainedSize <= 65536);
            for (Http2Headers request : requests) {
                publicEncoder.encodeHeaders(3, request, buf);
                // Five indexed fields, each at most three bytes. Parse every representation,
                // rather than accepting a round trip that silently reinserts evicted paths.
                int fields = 0;
                for (int i = buf.readerIndex(); i < buf.writerIndex(); ++i) {
                    int first = buf.getUnsignedByte(i);
                    assertEquals(0x80, first & 0x80);
                    if ((first & 0x7f) == 0x7f) {
                        while ((buf.getUnsignedByte(++i) & 0x80) != 0) {
                            assertTrue(i < buf.writerIndex());
                        }
                    }
                    ++fields;
                }
                assertEquals(5, fields);
                assertEquals(request, decoder.decodeHeaders(3, buf));
                buf.clear();
            }
        } finally {
            publicEncoder.close();
            buf.release();
        }
    }

    private static Http2Headers headers() {
        return new DefaultHttp2Headers().method(new AsciiString("GET")).add(new AsciiString("a"), new AsciiString("1"))
                .add(new AsciiString("a"), new AsciiString("2"));
    }
}
