/*
 * Copyright 2026 The Netty Project
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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.microbench.util.AbstractMicrobenchmark;
import io.netty.util.AsciiString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Netty encoding synthetic browser-shaped requests, NOT a browser encoder or a traffic trace. */
@State(Scope.Thread)
@Threads(1)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HpackBrowserBenchmark extends AbstractMicrobenchmark {
    private static final int REQUESTS = 64;
    private static final int SESSIONS = 8;
    private static final long SEED = 0x42524f57534552L;
    private static final String ORIGIN = "https://shop.example.test";

    public enum Workload { NORMAL, AUTHENTICATED }
    public enum CookiePolicy { NEVER, INDEX_COOKIES }

    @Param({ "4096" })
    public int tableSize;
    @Param
    public Workload workload;
    @Param
    public CookiePolicy cookiePolicy;
    @Param({ "true" })
    public boolean splitCookies;

    private Http2Headers[][] sessions;
    private Http2HeadersEncoder.SensitivityDetector sensitivity;
    private DefaultHttp2HeadersEncoder persistent;
    private Field encoderField;
    private ByteBuf output;
    private int sessionIndex;

    @Setup
    public void setup() throws Exception {
        if (tableSize != 0 && tableSize != 256 && tableSize != 4096 && tableSize != 65536) {
            throw new IllegalArgumentException("Supported table sizes: 0, 256, 4096, 65536");
        }
        sensitivity = new Http2HeadersEncoder.SensitivityDetector() {
            @Override
            public boolean isSensitive(CharSequence name, CharSequence value) {
                return AsciiString.contentEquals(name, "authorization") ||
                        cookiePolicy == CookiePolicy.NEVER && AsciiString.contentEquals(name, "cookie");
            }
        };
        // Resolve once in setup, never in construction/configuration or timed encoding.
        encoderField = DefaultHttp2HeadersEncoder.class.getDeclaredField("hpackEncoder");
        encoderField.setAccessible(true);
        sessions = fixtures();
        output = Unpooled.buffer(32768, 32768);
        try {
            validate(null);
            persistent = newEncoder();
            if (persistent.configuration().maxHeaderTableSize() != tableSize) {
                throw new IllegalStateException("Requested capacity was clamped: " + tableSize + " -> " +
                        persistent.configuration().maxHeaderTableSize());
            }
            // Warm the actual timed encoder independently; no captured-wire replay or periodicity assumption.
            for (Http2Headers[] session : sessions) {
                encodeSession(persistent, session);
            }
            // A configured limit alone is not evidence that this fixture exercises large-table retention.
            if (tableSize == 65536 && table(persistent).size() <= 4096) {
                throw new IllegalStateException("64 KiB fixture retained no more than 4 KiB");
            }
        } catch (Exception cause) {
            tearDown();
            throw cause;
        }
    }

    private DefaultHttp2HeadersEncoder newEncoder() throws Http2Exception {
        DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder(sensitivity, false);
        encoder.configuration().maxHeaderTableSize(tableSize);
        return encoder;
    }

    // Validation only. Read after configuration, which may replace the internal encoder.
    private HpackEncoder table(DefaultHttp2HeadersEncoder encoder) throws IllegalAccessException {
        return (HpackEncoder) encoderField.get(encoder);
    }

    private Http2Headers[][] fixtures() {
        Random random = new Random(SEED);
        String[] assets = new String[48];
        for (int i = 0; i < assets.length; ++i) {
            assets[i] = opaque(random).substring(0, 16);
        }
        // Stable for this entire synthetic user, not a newly generated cookie on every request/session.
        String[] cookies = workload == Workload.NORMAL ?
                new String[] { "consent=essential", "theme=light", "visit=synth-" + opaque(random) } :
                new String[] { "consent=essential", "theme=dark", "session=synth-" + opaque(random) +
                        opaque(random), "csrf=synth-" + opaque(random), "preferences=synth-" + opaque(random),
                        "experiment=synth-" + opaque(random), "cart=synth-" + opaque(random) + opaque(random) };
        String bearer = new StringBuilder().append("Bearer").append(' ')
                .append("SYNTHETIC_NOT_A_CREDENTIAL_").append(opaque(random)).append(opaque(random)).toString();
        Http2Headers[][] result = new Http2Headers[SESSIONS][REQUESTS];
        for (int s = 0; s < SESSIONS; ++s) {
            String page = "/catalog/" + uuid(random);
            for (int r = 0; r < REQUESTS; ++r) {
                // NORMAL: 1 document, 4 CSS, 12 JS, 32 images, 4 fonts, 11 APIs.
                // AUTHENTICATED: 1 document, 4 CSS, 12 JS, 16 images, 4 fonts, 27 APIs.
                int imageEnd = workload == Workload.NORMAL ? 49 : 33;
                String dest = r == 0 ? "document" : r < 5 ? "style" : r < 17 ? "script" :
                        r < imageEnd ? "image" : r < imageEnd + 4 ? "font" : "empty";
                boolean api = "empty".equals(dest);
                boolean post = api && workload == Workload.AUTHENTICATED && r % 4 == 0;
                String path = r == 0 ? page : api ? "/api/items/" + uuid(random) + "?view=summary" :
                        "/assets/" + dest + "/" + assets[(r - 1) % assets.length] +
                        ("style".equals(dest) ? ".css" : "script".equals(dest) ? ".js" :
                                "font".equals(dest) ? ".woff2" : ".avif");
                Http2Headers h = new DefaultHttp2Headers();
                h.method(post ? "POST" : "GET").scheme("https").authority("shop.example.test").path(path);
                add(h, "sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
                add(h, "sec-ch-ua-mobile", "?0");
                add(h, "sec-ch-ua-platform", "\"Linux\"");
                add(h, "user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                add(h, "accept", r == 0 ? "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,*/*;q=0.8" : "style".equals(dest) ? "text/css,*/*;q=0.1" :
                        "image".equals(dest) ? "image/avif,image/webp,image/apng,image/*,*/*;q=0.8" :
                                api ? "application/json" : "*/*");
                add(h, "accept-encoding", "gzip, deflate, br, zstd");
                add(h, "accept-language", "en-US,en;q=0.9");
                add(h, "sec-fetch-site", r == 0 ? "none" : "same-origin");
                add(h, "sec-fetch-mode", r == 0 ? "navigate" : api || "font".equals(dest) ? "cors" : "no-cors");
                add(h, "sec-fetch-dest", dest);
                add(h, "priority", r == 0 || "style".equals(dest) ? "u=0" :
                        "font".equals(dest) ? "u=1" : api ? "u=1, i" : "image".equals(dest) ? "u=4, i" : "u=2");
                if (r == 0) {
                    add(h, "sec-fetch-user", "?1");
                    add(h, "upgrade-insecure-requests", "1");
                } else {
                    add(h, "referer", ORIGIN + page);
                }
                if ("font".equals(dest) || post) {
                    add(h, "origin", ORIGIN);
                }
                if (post) {
                    add(h, "content-type", "application/json");
                    add(h, "content-length", "128");
                }
                if (api && workload == Workload.AUTHENTICATED) {
                    add(h, "authorization", bearer);
                }
                if (splitCookies) {
                    for (String cookie : cookies) {
                        add(h, "cookie", cookie);
                    }
                } else {
                    add(h, "cookie", String.join("; ", cookies));
                }
                result[s][r] = h;
            }
        }
        return result;
    }

    private static String uuid(Random random) {
        // RFC 4122 version/variant bits, with deterministic synthetic randomness.
        return new UUID(random.nextLong() & 0xffffffffffff0fffL | 0x4000L,
                random.nextLong() & 0x3fffffffffffffffL | 0x8000000000000000L).toString();
    }

    private static String opaque(Random random) {
        return uuid(random).replace("-", "");
    }

    private static void add(Http2Headers headers, String name, String value) {
        headers.add(AsciiString.cached(name), new AsciiString(value));
    }

    private int encodeSession(DefaultHttp2HeadersEncoder encoder, Http2Headers[] session) throws Http2Exception {
        int bytes = 0;
        for (int r = 0; r < REQUESTS; ++r) {
            output.clear();
            encoder.encodeHeaders(3, session[r], output);
            bytes += output.readableBytes();
        }
        return bytes;
    }

    /** Warm persistent compression context; finite fixture corpus repeats, encoder state is not reset. */
    @Benchmark
    @OperationsPerInvocation(REQUESTS)
    public int persistentSession() throws Http2Exception {
        return encodeSession(persistent, sessions[sessionIndex++ & (SESSIONS - 1)]);
    }

    /** Constructor/configuration and all 64 blocks, including the first, are inside the measurement. */
    @Benchmark
    @OperationsPerInvocation(REQUESTS)
    public int newConnectionSession() throws Http2Exception {
        return encodeSession(newEncoder(), sessions[sessionIndex++ & (SESSIONS - 1)]);
    }

    /** Diagnostic only, not a quantity to subtract from encoding timings. */
    @Benchmark
    @OperationsPerInvocation(REQUESTS)
    public void traverseHeaders(Blackhole blackhole) {
        for (Http2Headers h : sessions[sessionIndex++ & (SESSIONS - 1)]) {
            for (Map.Entry<CharSequence, CharSequence> field : h) {
                blackhole.consume(field.getKey());
                blackhole.consume(field.getValue());
            }
        }
    }

    private void validate(PrintStream manifest) throws Exception {
        MessageDigest fixtureHash = MessageDigest.getInstance("SHA-256");
        if (manifest != null) {
            manifest.println("fixture-v1 seed=" + SEED + " workload=" + workload + " cookiePolicy=" + cookiePolicy +
                    " splitCookies=" + splitCookies + " tableSize=" + tableSize);
        }
        for (int s = 0; s < SESSIONS; ++s) {
            for (int r = 0; r < REQUESTS; ++r) {
                boolean regular = false;
                int pseudo = 0;
                for (Map.Entry<CharSequence, CharSequence> field : sessions[s][r]) {
                    String name = field.getKey().toString();
                    if (name.charAt(0) == ':') {
                        if (regular) {
                            throw new IllegalStateException("Pseudo-header after regular field");
                        }
                        ++pseudo;
                    } else {
                        regular = true;
                    }
                    if (name.equals("connection") || name.equals("keep-alive") || name.equals("transfer-encoding") ||
                            name.equals("upgrade") || name.equals("proxy-connection")) {
                        throw new IllegalStateException("HTTP/2 connection-specific header");
                    }
                    String line = "header " + s + '/' + r + ' ' + name + "=" + field.getValue();
                    fixtureHash.update((line + '\n').getBytes(StandardCharsets.US_ASCII));
                    if (manifest != null) {
                        manifest.println(line);
                    }
                }
                if (pseudo != 4) {
                    throw new IllegalStateException("Expected four request pseudo-headers");
                }
            }
        }
        if (manifest != null) {
            manifest.println("fixture-sha256=" + hex(fixtureHash.digest()));
        }
        for (boolean fresh : new boolean[] { true, false }) {
            DefaultHttp2HeadersEncoder encoder = null;
            HpackEncoder table = null;
            HpackDecoder decoder = null;
            MessageDigest wireHash = MessageDigest.getInstance("SHA-256");
            long total = 0;
            // Validate every finite new-connection session, and three continuous corpus rounds.
            for (int pass = 0; pass < (fresh ? 1 : 3); ++pass) {
                for (int s = 0; s < SESSIONS; ++s) {
                    if (fresh || encoder == null) {
                        encoder = newEncoder();
                        table = table(encoder);
                        decoder = new HpackDecoder(65536);
                        decoder.setMaxHeaderTableSize(tableSize);
                    }
                    for (int r = 0; r < REQUESTS; ++r) {
                        Http2Headers expected = sessions[s][r];
                        output.clear();
                        encoder.encodeHeaders(3, expected, output);
                        assertRepresentations(output.duplicate(), expected);
                        int length = output.readableBytes();
                        total += length;
                        for (int shift = 24; shift >= 0; shift -= 8) {
                            wireHash.update((byte) (length >>> shift));
                        }
                        byte[] bytes = new byte[length];
                        output.getBytes(0, bytes);
                        wireHash.update(bytes);
                        if (manifest != null) {
                            manifest.println("wire " + (fresh ? "new" : "persistent") + ' ' +
                                    pass + '/' + s + '/' + r + " bytes=" + length +
                                    " tableBytes=" + table.size() + " entries=" + table.length() +
                                    " hex=" + ByteBufUtil.hexDump(output));
                        }
                        Http2Headers actual = new DefaultHttp2Headers();
                        decoder.decode(3, output, actual, true);
                        if (output.isReadable() || !expected.equals(actual)) {
                            throw new IllegalStateException("Round trip failed at " + pass + '/' + s + '/' + r);
                        }
                        for (int i = 0; i < table.length(); ++i) {
                            HpackHeaderField field = table.getHeaderField(i);
                            if (sensitivity.isSensitive(field.name, field.value)) {
                                throw new IllegalStateException("Sensitive field entered dynamic table");
                            }
                        }
                    }
                }
            }
            if (manifest != null) {
                int blocks = SESSIONS * REQUESTS * (fresh ? 1 : 3);
                manifest.println("summary " + (fresh ? "new" : "persistent") + " blocks=" + blocks +
                        " bytes=" + total + " bytesPerRequest=" + (double) total / blocks +
                        " lengthDelimitedWireSha256=" + hex(wireHash.digest()));
            }
        }
    }

    // Parse representation boundaries, not compressed text. One representation per expected field;
    // the independent decoder below verifies all actual names/values, including split-cookie duplicates.
    private void assertRepresentations(ByteBuf wire, Http2Headers expected) {
        for (Map.Entry<CharSequence, CharSequence> field : expected) {
            int first = wire.readUnsignedByte();
            while ((first & 0xe0) == 0x20) {
                integer(wire, first, 5);
                first = wire.readUnsignedByte();
            }
            boolean indexed = (first & 0x80) != 0;
            boolean incremental = !indexed && (first & 0x40) != 0;
            boolean never = (first & 0xf0) == 0x10;
            if (sensitivity.isSensitive(field.getKey(), field.getValue()) && !never) {
                throw new IllegalStateException("Sensitive field not encoded NEVER indexed: " + field.getKey());
            }
            int index = integer(wire, first, indexed ? 7 : incremental ? 6 : 4);
            if (!indexed) {
                if (index == 0) {
                    skipString(wire);
                }
                skipString(wire);
            }
        }
        if (wire.isReadable()) {
            throw new IllegalStateException("Unexpected trailing representation");
        }
    }

    private static int integer(ByteBuf wire, int first, int bits) {
        int mask = (1 << bits) - 1;
        int value = first & mask;
        if (value == mask) {
            int next;
            int shift = 0;
            do {
                next = wire.readUnsignedByte();
                value += (next & 127) << shift;
                shift += 7;
            } while ((next & 128) != 0);
        }
        return value;
    }

    private static void skipString(ByteBuf wire) {
        wire.skipBytes(integer(wire, wire.readUnsignedByte(), 7));
    }

    private static String hex(byte[] bytes) {
        return ByteBufUtil.hexDump(bytes);
    }

    @TearDown
    public void tearDown() {
        if (output != null) {
            output.release();
            output = null;
        }
    }

    /** Readiness/manifest entry point, not JMH and not a timing run. */
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("WORKLOAD COOKIE_POLICY splitCookies tableSize --check|--manifest");
        }
        if (!"--check".equals(args[4]) && !"--manifest".equals(args[4])) {
            throw new IllegalArgumentException("Expected --check or --manifest");
        }
        HpackBrowserBenchmark benchmark = new HpackBrowserBenchmark();
        benchmark.workload = Workload.valueOf(args[0]);
        benchmark.cookiePolicy = CookiePolicy.valueOf(args[1]);
        benchmark.splitCookies = Boolean.parseBoolean(args[2]);
        benchmark.tableSize = Integer.parseInt(args[3]);
        try {
            benchmark.setup();
            if ("--manifest".equals(args[4])) {
                benchmark.validate(System.out);
            }
            // Exercise both actual benchmark entry points outside timing as a readiness check.
            for (int i = 0; i < SESSIONS * 3; ++i) {
                benchmark.persistentSession();
            }
            for (int i = 0; i < SESSIONS * 3; ++i) {
                benchmark.newConnectionSession();
            }
            System.out.println("PASS " + benchmark.workload + ' ' + benchmark.cookiePolicy +
                    " splitCookies=" + benchmark.splitCookies + " tableSize=" + benchmark.tableSize);
        } finally {
            benchmark.tearDown();
        }
    }
}
