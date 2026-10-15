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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Random;

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
        hpackEncoder = hpackEncoder.withMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        hpackDecoder.setMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(64 * 1024, hpackDecoder.getMaxHeaderTableSize());
    }

    @Test
    public void testSetMaxHeaderTableSizeBelowCap() throws Http2Exception {
        hpackEncoder = hpackEncoder.withMaxHeaderTableSize(2048);
        hpackDecoder.setMaxHeaderTableSize(2048);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(2048, hpackEncoder.getMaxHeaderTableSize());
        assertEquals(2048, hpackDecoder.getMaxHeaderTableSize());

        buf.clear();
        hpackEncoder = hpackEncoder.withMaxHeaderTableSize(0);
        hpackDecoder.setMaxHeaderTableSize(0);
        hpackEncoder.encodeHeaders(0, buf, EmptyHttp2Headers.INSTANCE, Http2HeadersEncoder.NEVER_SENSITIVE);
        hpackDecoder.decode(0, buf, mockHeaders, true);
        assertEquals(0, hpackEncoder.getMaxHeaderTableSize());
        assertEquals(0, hpackDecoder.getMaxHeaderTableSize());
    }

    @Test
    public void testSetMaxHeaderTableSizeCapIndependentOfSizeHint() throws Http2Exception {
        HpackEncoder encoder = new HpackEncoder(false, 16, HpackEncoder.HUFF_CODE_THRESHOLD);
        encoder = encoder.withMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        assertEquals(64 * 1024, encoder.getMaxHeaderTableSize());

        encoder = new HpackEncoder(false, 128, HpackEncoder.HUFF_CODE_THRESHOLD);
        encoder = encoder.withMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE);
        assertEquals(64 * 1024, encoder.getMaxHeaderTableSize());
    }

    @Test
    public void testConfigurationWithoutGrowthRetainsEncoder() throws Http2Exception {
        Assertions.assertSame(hpackEncoder, hpackEncoder.withMaxHeaderTableSize(4096));
        Assertions.assertSame(hpackEncoder, hpackEncoder.withMaxHeaderTableSize(2048));
        Assertions.assertSame(hpackEncoder, hpackEncoder.withMaxHeaderTableSize(0));
    }

    @Test
    public void testSetMaxHeaderTableSizeOverflow() {
        assertThrows(Http2Exception.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                hpackEncoder = hpackEncoder.withMaxHeaderTableSize(MAX_HEADER_TABLE_SIZE + 1);
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
    public void testFinalRawBucketsAcrossTableSizes() throws Exception {
        Method bucket = HpackEncoder.class.getDeclaredMethod("bucket", int.class);
        bucket.setAccessible(true);
        Field mask = HpackEncoder.class.getDeclaredField("hashMask");
        mask.setAccessible(true);
        assertEquals(short.class, mask.getType());
        assertTrue(Modifier.isFinal(mask.getModifiers()));
        Field names = HpackEncoder.class.getDeclaredField("nameEntries");
        Field pairs = HpackEncoder.class.getDeclaredField("nameValueEntries");
        names.setAccessible(true);
        pairs.setAccessible(true);
        assertTrue(Modifier.isFinal(names.getModifiers()));
        assertTrue(Modifier.isFinal(pairs.getModifiers()));
        int expectedBuckets = 64;
        int[] capacities = { 256, 4096, 8192, 16384, 32768, 65536, 256 };
        for (int i = 0; i < capacities.length; ++i) {
            setMaxTableSize(capacities[i]);
            expectedBuckets = Math.max(expectedBuckets, capacities[i] / 64);
            assertEquals(expectedBuckets - 1, mask.getInt(hpackEncoder));
            for (int h : new int[] { 0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 123456789 }) {
                assertEquals(h & mask.getInt(hpackEncoder), ((Integer) bucket.invoke(hpackEncoder, h)).intValue());
            }
        }
    }

    @Test
    public void testInitialAllocationAndGrowthWithSmallHints() throws Exception {
        Field names = HpackEncoder.class.getDeclaredField("nameEntries");
        Field pairs = HpackEncoder.class.getDeclaredField("nameValueEntries");
        names.setAccessible(true);
        pairs.setAccessible(true);
        int[] hints = { 0, 1, 2, 16, 64, 128, 1024 };
        int[] buckets = { 2, 2, 2, 16, 64, 128, 128 };
        for (int i = 0; i < hints.length; ++i) {
            HpackEncoder encoder = new HpackEncoder(false, hints[i], HpackEncoder.HUFF_CODE_THRESHOLD);
            Object[] originalNames = (Object[]) names.get(encoder);
            Object[] originalPairs = (Object[]) pairs.get(encoder);
            assertEquals(buckets[i], originalNames.length);
            assertEquals(buckets[i], originalPairs.length);
            encoder = encoder.withMaxHeaderTableSize(65536);
            for (int j = 0; j < 1400; ++j) {
                encoder.encodeHeaders(0, buf, new DefaultHttp2Headers().add("x-token", dispersedValue(j)),
                        Http2HeadersEncoder.NEVER_SENSITIVE);
                buf.clear();
            }
            assertEquals(1024, ((Object[]) pairs.get(encoder)).length);
            assertTrue(encoder.size() > 64000);
            for (Object first : (Object[]) pairs.get(encoder)) {
                if (first == null) {
                    continue;
                }
                Field next = first.getClass().getDeclaredField("next");
                next.setAccessible(true);
                int length = 0;
                for (Object entry = first; entry != null; entry = next.get(entry)) {
                    assertTrue(++length <= 16);
                }
            }
            Object grownNames = names.get(encoder);
            Object grownPairs = pairs.get(encoder);
            encoder = encoder.withMaxHeaderTableSize(0);
            encoder = encoder.withMaxHeaderTableSize(65536);
            Assertions.assertSame(grownNames, names.get(encoder));
            Assertions.assertSame(grownPairs, pairs.get(encoder));
        }
    }

    @Test
    public void testPopulatedReplacementIsIndependentAndPreservesPendingMinimum() throws Exception {
        setMaxTableSize(6144);
        for (int i = 0; i < 100; ++i) {
            roundTripAndClear("x-copy-" + i % 10, dispersedValue(i));
        }
        HpackEncoder original = hpackEncoder;
        original.setMaxHeaderListSize(12345);
        // A pending minimum must survive a subsequent replacement without changing the source.
        hpackEncoder = hpackEncoder.withMaxHeaderTableSize(6000);
        setMaxTableSize(65536);
        Assertions.assertNotSame(original, hpackEncoder);
        assertEquals(6000, original.getMaxHeaderTableSize());
        assertEquals(12345, hpackEncoder.getMaxHeaderListSize());
        assertEquals(original.size(), hpackEncoder.size());
        for (int i = 0; i < original.length(); ++i) {
            HpackHeaderField oldField = original.getHeaderField(i);
            HpackHeaderField newField = hpackEncoder.getHeaderField(i);
            Assertions.assertNotSame(oldField, newField);
            Assertions.assertSame(oldField.name, newField.name);
            Assertions.assertSame(oldField.value, newField.value);
        }
        Field names = HpackEncoder.class.getDeclaredField("nameEntries");
        names.setAccessible(true);
        java.util.Set<Object> oldNodes = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Object, Boolean>());
        for (Object first : (Object[]) names.get(original)) {
            if (first != null) {
                Field next = first.getClass().getDeclaredField("next");
                next.setAccessible(true);
                for (Object entry = first; entry != null; entry = next.get(entry)) {
                    oldNodes.add(entry);
                }
            }
        }
        for (Object first : (Object[]) names.get(hpackEncoder)) {
            if (first != null) {
                Field next = first.getClass().getDeclaredField("next");
                next.setAccessible(true);
                for (Object entry = first; entry != null; entry = next.get(entry)) {
                    assertTrue(!oldNodes.contains(entry));
                }
            }
        }
        // Observe both pending updates, and then decode them against the populated peer.
        hpackEncoder.encodeHeaders(0, buf, new DefaultHttp2Headers(), Http2HeadersEncoder.NEVER_SENSITIVE);
        ByteBuf expected = Unpooled.buffer();
        try {
            HpackEncoder pending = new HpackEncoder().withMaxHeaderTableSize(6000).withMaxHeaderTableSize(65536);
            pending.encodeHeaders(0, expected, new DefaultHttp2Headers(), Http2HeadersEncoder.NEVER_SENSITIVE);
            assertEquals(expected, buf);
        } finally {
            expected.release();
        }
        hpackDecoder.decode(0, buf, new DefaultHttp2Headers(), true);
        buf.clear();
        // Clear the original: none of the cloned links or name representatives may be unlinked.
        original.withMaxHeaderTableSize(0);
        for (int i = 0; i < 100; ++i) {
            roundTripAndClear("x-copy-" + i % 10, dispersedValue(i));
            assertEquals(100, hpackEncoder.length());
        }
        original = original.withMaxHeaderTableSize(65536);
        original.encodeHeaders(0, buf, new DefaultHttp2Headers().add("x-independent", "v"),
                Http2HeadersEncoder.NEVER_SENSITIVE);
        assertEquals(1, original.length());
        assertEquals(100, hpackEncoder.length());
        buf.clear();
    }

    @Test
    public void testInvalidGrowthDoesNotChangeState() throws Exception {
        roundTripAndClear("x-before", "v");
        HpackEncoder original = hpackEncoder;
        for (long invalid : new long[] { -1, MAX_HEADER_TABLE_SIZE + 1 }) {
            Assertions.assertThrows(Http2Exception.class, () -> original.withMaxHeaderTableSize(invalid));
            assertEquals(4096, original.getMaxHeaderTableSize());
            assertEquals(1, original.length());
        }
        verifyEncoding(new DefaultHttp2Headers().add("x-before", "v"), 0xbe);
        Assertions.assertSame(original, original.withMaxHeaderTableSize(4096));
    }

    @Test
    public void testReplacementPreservesIgnoreAndHuffmanConfiguration() throws Exception {
        HpackEncoder encoder = new HpackEncoder(true, 64, 0);
        encoder.setMaxHeaderListSize(0);
        encoder = encoder.withMaxHeaderTableSize(65536);
        encoder.encodeHeaders(0, buf, new DefaultHttp2Headers(), Http2HeadersEncoder.NEVER_SENSITIVE);
        buf.clear();
        encoder.encodeHeaders(0, buf, new DefaultHttp2Headers().add("cookie", "aaaaaaaa"),
                Http2HeadersEncoder.ALWAYS_SENSITIVE);
        assertEquals(0x80, buf.getUnsignedByte(2) & 0x80);
    }

    @Test
    public void testDecimalValuesRaw1024BucketCapacity() throws Exception {
        setMaxTableSize(65536);
        for (int i = 0; i < 1600; ++i) {
            roundTripAndClear("x-decimal", Integer.toString(i));
        }
        // Raw hashing still clusters decimal suffixes: bucket growth alone is not a guarantee
        // of near-64 KiB retention. Keep this honest alongside the full-capacity token tests.
        assertTrue(hpackEncoder.size() < 64000);
        assertTrue(hpackEncoder.size() > 4096);
        assertChainBounds(hpackEncoder, 16);
    }

    private static void assertChainBounds(HpackEncoder encoder, int bound) throws Exception {
        for (String fieldName : new String[] { "nameEntries", "nameValueEntries" }) {
            Field field = HpackEncoder.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            for (Object first : (Object[]) field.get(encoder)) {
                if (first == null) {
                    continue;
                }
                Field next = first.getClass().getDeclaredField("next");
                next.setAccessible(true);
                int length = 0;
                for (Object entry = first; entry != null; entry = next.get(entry)) {
                    // Check inside the traversal: a cycle must fail, not hang the test.
                    assertTrue(++length <= bound);
                }
            }
        }
    }

    @Test
    public void testLargeTableManyEntriesAndEviction() throws Http2Exception {
        setMaxTableSize(65536);
        for (int i = 0; i < 1200; ++i) {
            roundTripAndClear("x-many", dispersedValue(i));
        }
        assertEquals(1200, hpackEncoder.length());
        assertTrue(hpackEncoder.size() > 4096);
        // All entries, including indexes requiring more than one byte, must remain reusable.
        for (int i = 0; i < 1200; ++i) {
            roundTripAndClear("x-many", dispersedValue(i));
            assertEquals(1200, hpackEncoder.length());
        }
        for (int i = 1200; i < 4000; ++i) {
            roundTripAndClear("x-many", dispersedValue(i));
            assertTrue(hpackEncoder.size() <= 65536);
        }
        assertEquals(dispersedValue(3999), hpackEncoder.getHeaderField(0).value.toString());
        setMaxTableSize(4096);
        roundTripAndClear("x-many", dispersedValue(3999));
        assertTrue(hpackEncoder.size() <= 4096);
        setMaxTableSize(0);
        roundTripAndClear("x-many", "value-3999");
        assertEquals(0, hpackEncoder.length());
        setMaxTableSize(65536);
        roundTripAndClear("x-many", "value-0");
        assertEquals(1, hpackEncoder.length());
    }

    @Test
    public void testLargeTableManyNamesAndResizeChurn() throws Exception {
        setMaxTableSize(65536);
        for (int i = 0; i < 1200; ++i) {
            roundTripAndClear("x-" + dispersedValue(i), "v");
        }
        assertEquals(1200, hpackEncoder.length());
        for (int i = 0; i < 1200; ++i) {
            roundTripAndClear("x-" + dispersedValue(i), "v");
            assertEquals(1200, hpackEncoder.length());
        }
        Field namesField = HpackEncoder.class.getDeclaredField("nameEntries");
        namesField.setAccessible(true);
        Object names = namesField.get(hpackEncoder);
        for (int i = 0; i < 20; ++i) {
            setMaxTableSize(0);
            setMaxTableSize(65536);
        }
        Assertions.assertSame(names, namesField.get(hpackEncoder));
        roundTripAndClear("x-name-0", "new-value");
        assertEquals(1, hpackEncoder.length());
    }

    @Test
    public void testLargeTableWithInheritedCollisionChain() throws Http2Exception {
        for (int i = 0; i < 80; ++i) {
            roundTripAndClear("x-collision", collidingValue(i));
        }
        assertEquals(80, hpackEncoder.length());
        setMaxTableSize(65536);
        for (int i = 80; i < 128; ++i) {
            roundTripAndClear("x-collision", collidingValue(i));
            assertEquals(80, hpackEncoder.length());
        }
        // The oldest entry is beyond the admission limit, but must still encode as an index.
        verifyEncoding(new DefaultHttp2Headers().add("x-collision", collidingValue(0)), 0xff, 14);
        // The inherited chain remains usable, but cannot grow. Removing its oldest entries
        // must preserve the name entry pointing at the newest surviving value.
        for (int i = 0; i < 80; ++i) {
            roundTripAndClear("x-collision", collidingValue(i));
        }
        setMaxTableSize(200);
        roundTripAndClear("x-collision", collidingValue(79));
        setMaxTableSize(65536);
        roundTripAndClear("x-collision", collidingValue(80));
        assertEquals(4, hpackEncoder.length());
    }

    @Test
    public void testInheritedNameChainDoesNotGrow() throws Exception {
        String first = null;
        int inserted = 0;
        for (int i = 0; inserted < 96 && i < 1000000; ++i) {
            String name = "x-" + dispersedValue(i);
            if ((AsciiString.hashCode(name) & 1023) != 0) {
                continue;
            }
            if (inserted == 0) {
                first = name;
            }
            if (inserted == 80) {
                assertEquals(80, hpackEncoder.length());
                setMaxTableSize(65536);
            }
            roundTripAndClear(name, "v");
            ++inserted;
            assertEquals(Math.min(inserted, 80), hpackEncoder.length());
        }
        assertEquals(96, inserted);
        assertChainBounds(hpackEncoder, 128);
        // Name reuse at the end of an inherited chain remains legal; the pair hashes differ.
        roundTripAndClear(first, "new-value");
        assertEquals(81, hpackEncoder.length());
        verifyEncoding(new DefaultHttp2Headers().add(first, "new-value"), 0x80 | 62);
    }

    @Test
    public void testResizePopulatedTableAndCounterOverflow() throws Exception {
        // Seed an empty encoder just before signed integer wraparound. Subsequent counters
        // are assigned normally; dynamic indexes must use modular subtraction.
        Field headField = HpackEncoder.class.getDeclaredField("head");
        headField.setAccessible(true);
        Object head = headField.get(hpackEncoder);
        Field counterField = head.getClass().getDeclaredField("counter");
        counterField.setAccessible(true);
        counterField.setInt(head, Integer.MIN_VALUE + 20);
        for (int i = 0; i < 40; ++i) {
            roundTripAndClear("x-wrap-" + i, "v");
        }
        for (int capacity : new int[] { 8192, 16384, 65536 }) {
            setMaxTableSize(capacity);
            for (int i = 0; i < 40; ++i) {
                roundTripAndClear("x-wrap-" + i, "v");
                assertEquals(40, hpackEncoder.length());
            }
        }
        setMaxTableSize(100);
        roundTripAndClear("x-wrap-39", "another");
        setMaxTableSize(65536);
        roundTripAndClear("x-wrap-0", "v");
    }

    @Test
    public void testMissAccountingAcrossNameKindsAndGrowth() throws Exception {
        for (int capacity : new int[] { 4096, 8192, 65536, 4096 }) {
            setMaxTableSize(capacity);
            for (int i = 0; i < 200; ++i) {
                // Static names, reused dynamic names, and new dynamic names exercise all insertion branches.
                String name = i % 3 == 0 ? "cookie" : i % 3 == 1 ? "x-shared" : "x-unique-" + i;
                roundTripAndClear(name, dispersedValue(i + capacity));
                long expectedSize = 0;
                for (int j = 0; j < hpackEncoder.length(); ++j) {
                    expectedSize += hpackEncoder.getHeaderField(j).size();
                }
                assertEquals(expectedSize, hpackEncoder.size());
                assertTrue(expectedSize <= capacity);
                int count = hpackEncoder.length();
                Http2Headers sensitive = new DefaultHttp2Headers().add(name, "secret");
                hpackEncoder.encodeHeaders(0, buf, sensitive, Http2HeadersEncoder.ALWAYS_SENSITIVE);
                Http2Headers decoded = new DefaultHttp2Headers();
                hpackDecoder.decode(0, buf, decoded, true);
                assertEquals(sensitive, decoded);
                buf.clear();
                assertEquals(expectedSize, hpackEncoder.size());
                assertEquals(count, hpackEncoder.length());
            }
        }
    }

    @Test
    public void testNameLookupAfterEvictingItsLastValue() throws Http2Exception {
        hpackDecoder.setMaxHeaderListSize(65536);
        String name = "x-evicted";
        for (int capacity : new int[] { 256, 4096, 8192, 65536 }) {
            setMaxTableSize(0);
            roundTripAndClear("x-clear", "v");
            setMaxTableSize(capacity);
            roundTripAndClear(name, "old");
            char[] chars = new char[capacity - 32 - name.length()];
            java.util.Arrays.fill(chars, 'a');
            // Admission sees an existing name, but making room removes its only value.
            roundTripAndClear(name, new String(chars));
            assertEquals(1, hpackEncoder.length());
            assertEquals(capacity, hpackEncoder.size());
            // Evict that value again, then prove the replacement name remains indexable.
            roundTripAndClear(name, "new");
            assertEquals(1, hpackEncoder.length());
            verifyEncoding(new DefaultHttp2Headers().add(name, "new"), 0x80 | 62);
        }
    }

    @Test
    public void testLargeTableRandomResizeAndEviction() throws Http2Exception {
        Random random = new Random(194);
        for (int i = 0; i < 10000; ++i) {
            if (i % 200 == 0) {
                setMaxTableSize(random.nextInt(65537));
            }
            roundTripAndClear("x-" + random.nextInt(200), "v-" + random.nextInt(2000));
            assertTrue(hpackEncoder.size() <= hpackEncoder.getMaxHeaderTableSize());
        }
    }

    @Test
    public void testLargeTableCollidingNames() throws Exception {
        setMaxTableSize(65536);
        Method bucket = HpackEncoder.class.getDeclaredMethod("bucket", int.class);
        bucket.setAccessible(true);
        int inserted = 0;
        // Deliberately collide in the bucket even when full hashes differ. Testing only exact
        // hash equality would miss unbounded traversal in lookup and removal.
        for (int i = 0; inserted < 128 && i < 1000000; ++i) {
            String name = "x-" + dispersedValue(i);
            if ((Integer) bucket.invoke(hpackEncoder, AsciiString.hashCode(name)) == 0) {
                // Spread pair hashes so this tests name admission independently of pair admission.
                roundTripAndClear(name, dispersedValue(inserted));
                ++inserted;
            }
        }
        assertEquals(128, inserted);
        assertEquals(16, hpackEncoder.length());
        assertChainBounds(hpackEncoder, 16);
        setMaxTableSize(0);
        roundTripAndClear("x-clear", "v");
        setMaxTableSize(65536);
        roundTripAndClear("x-clear", "v");
        assertEquals(1, hpackEncoder.length());
    }

    @Test
    public void testExistingNameInFullNameBucket() throws Exception {
        assertIndexesNameInFullBucket("x-existing", true);
    }

    @Test
    public void testStaticNameInFullNameBucket() throws Exception {
        assertIndexesNameInFullBucket("content-type", false);
    }

    private void assertIndexesNameInFullBucket(String name, boolean existing) throws Exception {
        setMaxTableSize(65536);
        Method bucket = HpackEncoder.class.getDeclaredMethod("bucket", int.class);
        bucket.setAccessible(true);
        int targetBucket = (Integer) bucket.invoke(hpackEncoder, AsciiString.hashCode(name));
        int inserted = 0;
        if (existing) {
            // Insert first so the existing name is at the end of the full chain.
            roundTripAndClear(name, "v");
            ++inserted;
        }
        for (int i = 0; inserted < 16 && i < 1000000; ++i) {
            String collision = "x-" + dispersedValue(i);
            if ((Integer) bucket.invoke(hpackEncoder, AsciiString.hashCode(collision)) == targetBucket) {
                roundTripAndClear(collision, "v");
                assertEquals(++inserted, hpackEncoder.length());
            }
        }
        assertEquals(16, inserted);
        roundTripAndClear(name, "new-value");
        assertEquals(17, hpackEncoder.length());
        assertChainBounds(hpackEncoder, 16);
        // The new value was indexed, not merely emitted as a literal.
        verifyEncoding(new DefaultHttp2Headers().add(name, "new-value"), 0x80 | 62);
    }

    @Test
    public void testLargeTableCollidingValuesAndSensitiveHeaders() throws Http2Exception {
        setMaxTableSize(65536);
        // AsciiString hashes ASCII case-insensitively. These distinct values have identical
        // full hashes, while value equality is case-sensitive.
        int hash = AsciiString.hashCode("aaaaaaaa");
        for (int i = 0; i < 256; ++i) {
            String value = collidingValue(i);
            assertEquals(hash, AsciiString.hashCode(value));
            roundTripAndClear("x-collision", value);
            assertEquals(Math.min(i + 1, 16), hpackEncoder.length());
        }
        assertEquals(16, hpackEncoder.length());
        // A rejected insertion did not evict existing entries, and hits still use indexes.
        verifyEncoding(new DefaultHttp2Headers().add("x-collision", "aaaaaaaa"), 0x80 | 77);
        Http2Headers sensitive = new DefaultHttp2Headers().add("x-collision", "secret");
        hpackEncoder.encodeHeaders(0, buf, sensitive, Http2HeadersEncoder.ALWAYS_SENSITIVE);
        assertEquals(0x10, buf.getUnsignedByte(0) & 0xf0);
        Http2Headers decoded = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, decoded, true);
        assertEquals(sensitive, decoded);
        assertEquals(16, hpackEncoder.length());
    }

    private static String dispersedValue(int i) {
        // Deterministic, distinct tokens. Sequential decimal strings exercise raw-hash collisions,
        // not capacity; collision admission has its own adversarial tests below.
        return Long.toHexString(new Random(i * 0x9e3779b9L).nextLong());
    }

    private static String collidingValue(int i) {
        char[] chars = "aaaaaaaa".toCharArray();
        for (int bit = 0; bit < chars.length; ++bit) {
            if ((i & 1 << bit) != 0) {
                chars[bit] = 'A';
            }
        }
        return new String(chars);
    }

    private void roundTripAndClear(String name, String value) throws Http2Exception {
        verifyRoundTrip(new DefaultHttp2Headers().add(name, value));
        assertEquals(hpackEncoder.length(), hpackDecoder.length());
        assertEquals(hpackEncoder.size(), hpackDecoder.size());
        buf.clear();
    }

    @Test
    public void testEagerHuffmanEncoderReuseAndLiteralFallback() throws Exception {
        Field field = HpackEncoder.class.getDeclaredField("hpackHuffmanEncoder");
        field.setAccessible(true);
        assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        Object encoder = field.get(hpackEncoder);
        Assertions.assertNotNull(encoder);
        setMaxTableSize(0);
        roundTripAndClear("x-short", "short");
        Assertions.assertSame(encoder, field.get(hpackEncoder));

        char[] chars = new char[HpackEncoder.HUFF_CODE_THRESHOLD];
        java.util.Arrays.fill(chars, '~'); // Huffman encoding would expand this value.
        roundTripAndClear("x-long", new String(chars));
        Assertions.assertSame(encoder, field.get(hpackEncoder));
        java.util.Arrays.fill(chars, 'a'); // Now take the Huffman path on the same instance.
        roundTripAndClear("x-long", new String(chars));
        Assertions.assertSame(encoder, field.get(hpackEncoder));
        roundTripAndClear("x-short", "short");
        Assertions.assertSame(encoder, field.get(hpackEncoder));
    }

    @Test
    public void testSensitiveCookiesBelowHuffmanThresholdRemainRawAndUnindexed() throws Exception {
        char[] chars = new char[HpackEncoder.HUFF_CODE_THRESHOLD - 1];
        java.util.Arrays.fill(chars, 'a');
        Http2Headers headers = new DefaultHttp2Headers().add("cookie", new String(chars));
        for (int i = 0; i < 64; ++i) {
            hpackEncoder.encodeHeaders(3, buf, headers, Http2HeadersEncoder.ALWAYS_SENSITIVE);
            assertEquals(0x10, buf.getUnsignedByte(0) & 0xf0);
            // The static cookie name takes two bytes; the value must use a raw length prefix.
            assertEquals(0, buf.getUnsignedByte(2) & 0x80);
            Http2Headers decoded = new DefaultHttp2Headers();
            hpackDecoder.decode(3, buf, decoded, true);
            assertEquals(headers, decoded);
            assertEquals(0, hpackEncoder.length());
            buf.clear();
        }
    }

    @Test
    public void testSensitiveStaticAndDynamicNameWireIndices() throws Http2Exception {
        roundTripAndClear("cookie", "old");
        roundTripAndClear("x-private", "old");
        // Static names never enter the name-only table, even when their values are indexed.
        hpackEncoder.encodeHeaders(3, buf, new DefaultHttp2Headers().add("cookie", "v"),
                Http2HeadersEncoder.ALWAYS_SENSITIVE);
        assertEquals("1f110176", io.netty.buffer.ByteBufUtil.hexDump(buf));
        buf.clear();
        hpackEncoder.encodeHeaders(3, buf, new DefaultHttp2Headers().add("x-private", "v"),
                Http2HeadersEncoder.ALWAYS_SENSITIVE);
        assertEquals("1f2f0176", io.netty.buffer.ByteBufUtil.hexDump(buf));
        assertEquals(2, hpackEncoder.length());
    }

    @Test
    public void testLiteralThresholdWireEquivalence() throws Http2Exception {
        // Exercise raw, expanding Huffman fallback, and compressed literals, with both input types.
        for (boolean ascii : new boolean[] { false, true }) {
            for (char fill : new char[] { 'a', '~' }) {
                for (int length : new int[] { 511, 512, 513 }) {
                    char[] chars = new char[length];
                    java.util.Arrays.fill(chars, fill);
                    CharSequence value = ascii ? new AsciiString(new String(chars)) : new String(chars);
                    Http2Headers headers = new DefaultHttp2Headers().add("cookie", value);
                    HpackEncoder reference = new HpackEncoder(false, 64,
                            length < HpackEncoder.HUFF_CODE_THRESHOLD ? Integer.MAX_VALUE : 0);
                    ByteBuf expected = Unpooled.buffer();
                    try {
                        reference.encodeHeaders(3, expected, headers, Http2HeadersEncoder.ALWAYS_SENSITIVE);
                        hpackEncoder.encodeHeaders(3, buf, headers, Http2HeadersEncoder.ALWAYS_SENSITIVE);
                        assertEquals(expected, buf);
                        Http2Headers decoded = new DefaultHttp2Headers();
                        hpackDecoder.decode(3, buf, decoded, true);
                        assertEquals(headers, decoded);
                        assertEquals(0, hpackEncoder.length());
                    } finally {
                        expected.release();
                        buf.clear();
                    }
                }
            }
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
        hpackEncoder = hpackEncoder.withMaxHeaderTableSize(maxHeaderTableSize);
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
