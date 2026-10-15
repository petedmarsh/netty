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
import io.netty.util.AsciiString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HpackEncoderAdmissionTest {
    private HpackEncoder hpackEncoder;
    private HpackDecoder hpackDecoder;
    private ByteBuf buf;

    @BeforeEach
    public void setUp() {
        // The existing 128-bucket constructor supports 8 KiB without growth.
        hpackEncoder = new HpackEncoder(false, 128, HpackEncoder.HUFF_CODE_THRESHOLD);
        hpackDecoder = new HpackDecoder(8192);
        buf = Unpooled.buffer();
    }

    @AfterEach
    public void tearDown() {
        buf.release();
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
    public void testLargeTableCollidingNames() throws Exception {
        setMaxTableSize(8192);
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
        setMaxTableSize(8192);
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
        setMaxTableSize(8192);
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
        setMaxTableSize(8192);
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
    public void testRejectedNameDoesNotEvictRetainedEntries() throws Exception {
        setMaxTableSize(8192);
        Method bucket = HpackEncoder.class.getDeclaredMethod("bucket", int.class);
        bucket.setAccessible(true);
        String value = new String(new char[400]).replace('\0', 'a');
        int inserted = 0;
        for (int i = 0; i < 1000000; ++i) {
            String name = "x-" + dispersedValue(i);
            if ((Integer) bucket.invoke(hpackEncoder, AsciiString.hashCode(name)) != 0) {
                continue;
            }
            if (inserted == 16) {
                long retainedSize = hpackEncoder.size();
                String rejectedValue = value + value + value + value + value;
                assertTrue(retainedSize + 32 + name.length() + rejectedValue.length() > 8192);
                roundTripAndClear(name, rejectedValue);
                assertEquals(retainedSize, hpackEncoder.size());
                assertEquals(16, hpackEncoder.length());
                return;
            }
            roundTripAndClear(name, value);
            assertEquals(++inserted, hpackEncoder.length());
        }
        throw new AssertionError("Insufficient colliding names");
    }

    @Test
    public void testDefaultTableRetainsOriginalAdmissionBehavior() throws Http2Exception {
        setMaxTableSize(4096);
        for (int i = 0; i < 32; ++i) {
            roundTripAndClear("x-collision", collidingValue(i));
        }
        assertEquals(32, hpackEncoder.length());
    }

    private void setMaxTableSize(int size) throws Http2Exception {
        hpackEncoder.setMaxHeaderTableSize(size);
        hpackDecoder.setMaxHeaderTableSize(size);
        assertEquals(size, hpackEncoder.getMaxHeaderTableSize());
    }

    private void verifyRoundTrip(Http2Headers headers) throws Http2Exception {
        hpackEncoder.encodeHeaders(0, buf, headers, Http2HeadersEncoder.NEVER_SENSITIVE);
        Http2Headers decoded = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, decoded, true);
        assertEquals(headers, decoded);
    }

    private void verifyEncoding(Http2Headers headers, int expected) throws Http2Exception {
        buf.clear();
        hpackEncoder.encodeHeaders(0, buf, headers, Http2HeadersEncoder.NEVER_SENSITIVE);
        assertEquals(1, buf.readableBytes());
        assertEquals(expected, buf.getUnsignedByte(buf.readerIndex()));
        Http2Headers decoded = new DefaultHttp2Headers();
        hpackDecoder.decode(0, buf, decoded, true);
        assertEquals(headers, decoded);
        buf.clear();
    }
}
