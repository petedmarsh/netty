/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderValidationUtil;
import io.netty.util.AsciiString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_HEADER_TABLE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class HpackEncoderTest {
    private HpackDecoder hpackDecoder;
    private HpackEncoder hpackEncoder;
    private Http2Headers mockHeaders;
    private ByteBuf buf;

    @BeforeEach
    public void setUp() {
        hpackEncoder = new HpackEncoder();
        hpackDecoder = new HpackDecoder(DEFAULT_HEADER_LIST_SIZE);
        mockHeaders = mock(Http2Headers.class);
        buf = Unpooled.buffer();
    }

    @AfterEach
    public void teardown() {
        buf.release();
    }

    @Test
    public void testSetMaxHeaderTableSizeToMaxValue() throws Http2Exception {
        hpackEncoder = new HpackEncoder(
            false, Integer.MAX_VALUE, HpackEncoder.HUFF_CODE_THRESHOLD);
        hpackEncoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        hpackDecoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(128 * 64, hpackDecoder.getMaxHeaderTableSize());
    }

    @Test
    public void testSetMaxHeaderTableSizeBelowCap() throws Http2Exception {
        hpackEncoder.setMaxHeaderTableSize(2048);
        hpackDecoder.setMaxHeaderTableSize(2048);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(2048, hpackEncoder.getMaxHeaderTableSize());
        assertEquals(2048, hpackDecoder.getMaxHeaderTableSize());

        buf.clear();
        hpackEncoder.setMaxHeaderTableSize(0);
        hpackDecoder.setMaxHeaderTableSize(0);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(0, hpackEncoder.getMaxHeaderTableSize());
        assertEquals(0, hpackDecoder.getMaxHeaderTableSize());
    }

    @Test
    public void testSetMaxHeaderTableSizeCapScalesWithSizeHint() throws Http2Exception {
        HpackEncoder encoder = new HpackEncoder(false, 16, HpackEncoder.HUFF_CODE_THRESHOLD);
        encoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        assertEquals(16 * 64, encoder.getMaxHeaderTableSize());

        encoder = new HpackEncoder(false, 128, HpackEncoder.HUFF_CODE_THRESHOLD);
        encoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        assertEquals(128 * 64, encoder.getMaxHeaderTableSize());
    }

    @Test
    public void testBucketMixesHighBitsWithoutChangingTableCap() throws Exception {
        Method bucket = HpackEncoder.class.getDeclaredMethod("bucket", int.class);
        bucket.setAccessible(true);
        for (int hint : new int[] { 2, 16, 64, 128, Integer.MAX_VALUE }) {
            HpackEncoder encoder = new HpackEncoder(false, hint, HpackEncoder.HUFF_CODE_THRESHOLD);
            int buckets = Math.min(hint, 128);
            int mask = buckets - 1;
            encoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
            assertEquals(buckets * 64, encoder.getMaxHeaderTableSize());
            Set<Integer> occupied = new HashSet<Integer>();
            // A deliberately low-bit-colliding synthetic corpus, not a real-traffic distribution claim.
            for (int i = 0; i < 1024; ++i) {
                int hash = i << 16;
                assertEquals(0, hash & mask);
                int mixed = hash ^ hash >>> 16;
                mixed *= 0x85ebca6b;
                int actual = (Integer) bucket.invoke(encoder, hash);
                assertEquals((mixed ^ mixed >>> 13) & mask, actual);
                occupied.add(actual);
            }
            assertTrue(occupied.size() > buckets / 2);
            for (int hash : new int[] { -1, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
                int mixed = hash ^ hash >>> 16;
                mixed *= 0x85ebca6b;
                assertEquals((mixed ^ mixed >>> 13) & mask, (int) (Integer) bucket.invoke(encoder, hash));
            }
        }
    }

    @Test
    public void testEqualFullHashCollisionsRoundTripThroughEviction() throws Http2Exception {
        // ASCII hashing discards these differing bits. Mixing cannot separate equal full hashes.
        AsciiString firstName = AsciiString.cached("x-q");
        AsciiString secondName = AsciiString.cached("x-1");
        AsciiString firstValue = AsciiString.cached("A");
        AsciiString secondValue = AsciiString.cached("a");
        assertEquals(firstName.hashCode(), secondName.hashCode());
        assertEquals(firstValue.hashCode(), secondValue.hashCode());
        hpackEncoder.setMaxHeaderTableSize(256);
        hpackDecoder.setMaxHeaderTableSize(256);
        for (int round = 0; round < 4; ++round) {
            for (int repeat = 0; repeat < 2; ++repeat) {
                Http2Headers input = new DefaultHttp2Headers()
                        .add(firstName, firstValue).add(secondName, secondValue)
                        .add(firstName, secondValue).add(secondName, firstValue);
                assertRoundTrip(input);
            }
            // Evict the colliding entries, exercising removal from both bucket chains.
            for (int i = 0; i < 8; ++i) {
                assertRoundTrip(new DefaultHttp2Headers().add("x-evict-" + i, "value"));
            }
            assertTrue(hpackEncoder.size() <= 256);
        }
    }

    private void assertRoundTrip(Http2Headers input) throws Http2Exception {
        buf.clear();
        hpackEncoder.encodeHeaders(0, buf, input, Http2HeadersEncoder.NEVER_SENSITIVE);
        Http2Headers output = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, output, false);
        assertEquals(input, output);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void testSetMaxHeaderTableSizeOverflow() {
        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                hpackEncoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE + 1);
            }
        });
    }

    /**
     * The encoder should not impose an arbitrary limit on the header size if
     * the server has not specified any limit.
     * @throws Http2Exception
     */
    @Test
    public void testWillEncode16MBHeaderByDefault() throws Http2Exception {
        String bigHeaderName = "x-big-header";
        int bigHeaderSize = 1024 * 1024 * 16;
        String bigHeaderVal = new String(new char[bigHeaderSize]).replace('\0', 'X');
        Http2Headers headersIn = new DefaultHttp2Headers().add(
                "x-big-header", bigHeaderVal);
        Http2Headers headersOut = new DefaultHttp2Headers();

        hpackEncoder.encodeHeaders(0, buf, headersIn, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.setMaxHeaderListSize(bigHeaderSize + 1024);
        hpackDecoder.decode(0, buf, headersOut, false);
        assertEquals(headersOut.get(bigHeaderName).toString(), bigHeaderVal);
    }

    @Test
    public void testSetMaxHeaderListSizeEnforcedAfterSet() throws Http2Exception {
        final Http2Headers headers = new DefaultHttp2Headers().add(
                "x-big-header",
                new String(new char[1024 * 16]).replace('\0', 'X')
        );

        hpackEncoder.setMaxHeaderListSize(1000);

        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                hpackEncoder.encodeHeaders(0, buf, headers, Http2HeadersEncoder.NEVER_SENSITIVE);
            }
        });
    }

    @Test
    public void testEncodeUsingBothStaticAndDynamicTable() throws Http2Exception {
        final Http2Headers headers = new DefaultHttp2Headers()
          // :method -> POST is found in the static table.
          .add(":method", "POST")

          // ":path" is found in the static table but only matches "/" and "/index.html".
          .add(":path", "/dev/null")

          // "accept-language" is found in the static table, but with no matching value.
          .add("accept-language", "fr")

          // k -> x is not in the static table.
          .add("k", "x");

        // :method -> POST gets encoded by reference.
        // :path -> /dev/null
        //      :path gets encoded by reference, /dev/null literally.
        // accept-language -> fr
        //      accept-language gets encoded by reference, fr literally.
        // k -> x
        //      both k and x get encoded literally.
        verifyEncoding(headers,
          -125, 68, 9, 47, 100, 101, 118, 47, 110, 117, 108, 108, 81, 2, 102, 114, 64, 1, 107, 1, 120);

        // encoded using references to previous headers.
        verifyEncoding(headers, -125, -64, -65, -66);
    }

    @Test
    public void testSameHeaderNameMultipleValues() throws Http2Exception {
        final Http2Headers headers = new DefaultHttp2Headers()
          .add("k", "x")
          .add("k", "y");

        // k -> x encoded literally, k -> y encoded by referencing k of the k -> x header,
        // y gets encoded literally.
        verifyEncoding(headers, 64, 1, 107, 1, 120, 126, 1, 121);

        // both k -> x and k -> y encoded by reference.
        verifyEncoding(headers, -65, -66);
    }

    @Test
    public void testEviction() throws Http2Exception {
        setMaxTableSize(2 * HpackHeaderField.HEADER_ENTRY_OVERHEAD + 3);

        // k -> x encoded literally
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), 63, 36, 64, 1, 107, 1, 120);

        // k -> x encoded by referencing the previously encoded k -> x.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), -66);

        // k -> x gets evicted
        verifyEncoding(new DefaultHttp2Headers().add("k", "y"), 64, 1, 107, 1, 121);

        // k -> x was evicted, so we are back to literal encoding.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), 64, 1, 107, 1, 120);
    }

    @Test
    public void testTableResize() throws Http2Exception {
        verifyEncoding(new DefaultHttp2Headers().add("k", "x").add("k", "y"), 64, 1, 107, 1, 120, 126, 1, 121);

        // k -> x gets encoded by referencing the previously encoded k -> x.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), -65);

        // k -> x gets evicted
        setMaxTableSize(2 * HpackHeaderField.HEADER_ENTRY_OVERHEAD + 3);

        // k -> x header was evicted, so we are back to literal encoding.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), 63, 36, 64, 1, 107, 1, 120);

        // make room for k -> y
        setMaxTableSize(1000);

        verifyEncoding(new DefaultHttp2Headers().add("k", "y"), 63, -55, 7, 126, 1, 121);

        // both k -> x and k -> y are encoded by reference.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x").add("k", "y"), -65, -66);
    }

    @Test
    public void testMultipleTableResize() throws Http2Exception {
        verifyEncoding(new DefaultHttp2Headers().add("k", "x").add("k", "y"), 64, 1, 107, 1, 120, 126, 1, 121);

        // Multiple decreases should only emit the final minimum table size update (1000).
        setMaxTableSize(2000);
        setMaxTableSize(1000);
        verifyEncoding(new DefaultHttp2Headers().add("k", "x"), 63, -55, 7, -65);

        // Shrinking and then increasing multiple times should only emit the minimum (67) and final (1000) sizes.
        // At 67 bytes, k -> x is evicted while k -> y remains in the table.
        setMaxTableSize(800);
        setMaxTableSize(2 * HpackHeaderField.HEADER_ENTRY_OVERHEAD + 3);
        setMaxTableSize(500);
        setMaxTableSize(1000);

        // 63, 36 encodes table size 67; 63, -55, 7 encodes table size 1000.
        // k -> x was evicted so it is encoded as literal with incremental indexing (referencing name k from k -> y),
        // and k -> y is still in the table so it is encoded by reference.
        verifyEncoding(new DefaultHttp2Headers().add("k", "x").add("k", "y"),
                63, 36, 63, -55, 7, 126, 1, 120, -65);

        // Subsequent resize should not reuse the previous minimum table size.
        setMaxTableSize(2000);
        verifyEncoding(new DefaultHttp2Headers().add("k", "x").add("k", "y"), 63, -79, 15, -66, -65);
    }

    @Test
    public void testManyHeaderCombinations() throws Http2Exception {
        final Random r = new Random(0);
        for (int i = 0; i < 50000; i++) {
            if (r.nextInt(10) == 0) {
                setMaxTableSize(r.nextBoolean() ? 0 : r.nextInt(4096));
            }
            verifyRoundTrip(new DefaultHttp2Headers()
              .add("k" + r.nextInt(20), "x" + r.nextInt(500))
              .add(":method", r.nextBoolean() ? "GET" : "POST")
              .add(":path", "/dev/null")
              .add("accept-language", String.valueOf(r.nextBoolean()))
            );
            buf.clear();
        }
    }

    @Test
    public void testSanitization() throws Http2Exception {
        final int headerValueSize = 300;
        // Prefix the header values with a legal character.
        // The first value character has different rules than the rest.
        StringBuilder actualHeaderValueBuilder = new StringBuilder("a");
        StringBuilder expectedHeaderValueBuilder = new StringBuilder("a");

        for (int i = 0; i < headerValueSize; i++) {
            if (i <= 255 && HttpHeaderValidationUtil.validateValidHeaderValue("a" + ((char) i)) != -1) {
                // This is not a legal header value character. Skip it.
                continue;
            }
            actualHeaderValueBuilder.append((char) i); // Use the index as the code point value of the character.
            if (i <= 255) {
                expectedHeaderValueBuilder.append((char) i);
            } else {
                expectedHeaderValueBuilder.append('?'); // Expect this character to be sanitized.
            }
        }
        String actualHeaderValue = actualHeaderValueBuilder.toString();
        String expectedHeaderValue = expectedHeaderValueBuilder.toString();
        HpackEncoder encoderWithHuffmanEncoding =
                new HpackEncoder(false, 64, 0); // Low Huffman code threshold.
        HpackEncoder encoderWithoutHuffmanEncoding =
                new HpackEncoder(false, 64, Integer.MAX_VALUE); // High Huffman code threshold.

        // Expect the same decoded header value regardless of whether Huffman encoding is enabled or not.
        verifyHeaderValueSanitization(encoderWithHuffmanEncoding, actualHeaderValue, expectedHeaderValue);
        verifyHeaderValueSanitization(encoderWithoutHuffmanEncoding, actualHeaderValue, expectedHeaderValue);
    }

    private void verifyHeaderValueSanitization(
            HpackEncoder encoder,
            String actualHeaderValue,
            String expectedHeaderValue
    ) throws Http2Exception {

        String headerKey = "some-key";
        Http2Headers toBeEncodedHeaders = new DefaultHttp2Headers().add(headerKey, actualHeaderValue);
        encoder.encodeHeaders(0, buf, toBeEncodedHeaders, Http2HeadersEncoder.NEVER_SENSITIVE);
        DefaultHttp2Headers decodedHeaders = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, decodedHeaders, true);
        buf.clear();
        String decodedHeaderValue = decodedHeaders.get(headerKey).toString();
        Assertions.assertEquals(expectedHeaderValue, decodedHeaderValue);
    }

    private void setMaxTableSize(int maxHeaderTableSize) throws Http2Exception {
        hpackEncoder.setMaxHeaderTableSize(maxHeaderTableSize);
        hpackDecoder.setMaxHeaderTableSize(maxHeaderTableSize);
    }

    private void verifyEncoding(Http2Headers encodedHeaders, int... encoding) throws Http2Exception {
        verifyRoundTrip(encodedHeaders);
        verifyEncodedBytes(encoding);
        buf.clear();
    }

    private void verifyRoundTrip(Http2Headers encodedHeaders) throws Http2Exception {
        hpackEncoder.encodeHeaders(0, buf, encodedHeaders, Http2HeadersEncoder.NEVER_SENSITIVE);
        DefaultHttp2Headers decodedHeaders = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, decodedHeaders, true);
        assertEquals(encodedHeaders, decodedHeaders);
    }

    private void verifyEncodedBytes(int... expectedEncoding) {
        // We want to copy everything that was written to the buffer.
        byte[] actualEncoding = new byte[buf.writerIndex()];
        buf.getBytes(0, actualEncoding);
        Assertions.assertArrayEquals(toByteArray(expectedEncoding), actualEncoding);
    }

    private byte[] toByteArray(int[] encoding) {
        byte[] expectedEncoding = new byte[encoding.length];
        for (int i = 0; i < encoding.length; i++) {
            expectedEncoding[i] = (byte) encoding[i];
        }
        return expectedEncoding;
    }
}
