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
import io.netty.buffer.Unpooled;
import io.netty.microbench.util.AbstractMicrobenchmark;
import io.netty.util.AsciiString;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Persistent, thread-confined HPACK contexts. One operation is one four-field header block. */
@State(Scope.Thread)
@Threads(1)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HpackStateBenchmark extends AbstractMicrobenchmark {
    private static final int CORPUS_SIZE = 2048;
    private static final int BUFFER_SIZE = 16384;

    public enum Scenario {
        REPEATED, WORKING_SET, WORKING_SET128, WORKING_SET384, WORKING_SET768,
        MIXED, CHURN, NAME_HIT, COLLISION, STATIC, SENSITIVE, HUFFMAN
    }

    @Param({ "0", "256", "4096" })
    public int tableSize;

    @Param
    public Scenario scenario;

    /** Grow the encoder's backing structures before returning to a 4 KiB working capacity. */
    @Param({ "false" })
    public boolean growThenShrink;

    private Http2Headers[] headers;
    private ByteBuf[] wire;
    private ByteBuf output;
    private DefaultHttp2HeadersEncoder encoder;
    private HpackDecoder decoder;
    private final DiscardHeaders sink = new DiscardHeaders();
    private Http2HeadersEncoder.SensitivityDetector sensitivity;
    private int encodeIndex;
    private int decodeIndex;
    private int traversalIndex;

    @Setup
    public void setup() throws Http2Exception {
        output = Unpooled.buffer(BUFFER_SIZE, BUFFER_SIZE);
        headers = new Http2Headers[CORPUS_SIZE];
        wire = new ByteBuf[CORPUS_SIZE];
        sensitivity = scenario == Scenario.SENSITIVE ? Http2HeadersEncoder.ALWAYS_SENSITIVE :
                Http2HeadersEncoder.NEVER_SENSITIVE;
        if (growThenShrink && tableSize != 4096) {
            throw new IllegalArgumentException("growThenShrink requires tableSize=4096");
        }
        encoder = newEncoder(growThenShrink ? 65536 : tableSize, sensitivity);
        if (growThenShrink) {
            encoder.configuration().maxHeaderTableSize(tableSize);
            if (encoder.configuration().maxHeaderTableSize() != tableSize) {
                throw new IllegalStateException("Encoder did not shrink to " + tableSize);
            }
        }
        decoder = new HpackDecoder(Integer.MAX_VALUE);
        decoder.setMaxHeaderTableSize(tableSize);
        for (int i = 0; i < CORPUS_SIZE; ++i) {
            headers[i] = newHeaders(i);
        }
        // The first cycle negotiates the table size and brings both peers into steady state.
        // Capture the second cycle; require identical bytes in a third cycle and validate all
        // decoded fields on every cycle, including both wrap boundaries. Never replay cold bytes.
        for (int pass = 0; pass < 3; ++pass) {
            for (int i = 0; i < CORPUS_SIZE; ++i) {
                output.clear();
                encoder.encodeHeaders(3, headers[i], output);
                if (pass == 1) {
                    wire[i] = output.copy();
                } else if (pass == 2 && !output.equals(wire[i])) {
                    throw new IllegalStateException("Non-periodic HPACK corpus at " + i);
                }
                Http2Headers actual = new DefaultHttp2Headers(false);
                decoder.decode(3, output, actual, false);
                if (output.isReadable() || !headers[i].equals(actual)) {
                    throw new IllegalStateException("HPACK round trip failed: " + pass + '/' + i);
                }
            }
        }
        // Validate the actual captured replay, not merely freshly generated equivalent blocks.
        for (int pass = 0; pass < 2; ++pass) {
            for (int i = 0; i < CORPUS_SIZE; ++i) {
                wire[i].readerIndex(0);
                Http2Headers actual = new DefaultHttp2Headers(false);
                decoder.decode(3, wire[i], actual, false);
                if (wire[i].isReadable() || !headers[i].equals(actual)) {
                    throw new IllegalStateException("HPACK replay failed: " + pass + '/' + i);
                }
            }
        }
    }

    /** Deterministic steady-state wire sizes, outside the timed benchmark. */
    public static void main(String[] args) throws Http2Exception {
        boolean growThenShrink = args.length != 0 && "--growThenShrink".equals(args[0]);
        System.out.println("tableSize,scenario,blocks,wireBytes,bytesPerBlock,growThenShrink");
        for (int index = growThenShrink ? 1 : 0; index < args.length; ++index) {
            String arg = args[index];
            for (Scenario scenario : Scenario.values()) {
                HpackStateBenchmark benchmark = new HpackStateBenchmark();
                benchmark.tableSize = Integer.parseInt(arg);
                benchmark.scenario = scenario;
                benchmark.growThenShrink = growThenShrink;
                try {
                    benchmark.setup();
                    long bytes = 0;
                    for (ByteBuf block : benchmark.wire) {
                        bytes += block.writerIndex();
                    }
                    System.out.println(arg + "," + scenario + "," + CORPUS_SIZE + "," + bytes + "," +
                            (double) bytes / CORPUS_SIZE + "," + growThenShrink);
                } finally {
                    benchmark.tearDown();
                }
            }
        }
    }

    static DefaultHttp2HeadersEncoder newEncoder(int tableSize) throws Http2Exception {
        return newEncoder(tableSize, Http2HeadersEncoder.NEVER_SENSITIVE);
    }

    static DefaultHttp2HeadersEncoder newEncoder(int tableSize,
            Http2HeadersEncoder.SensitivityDetector sensitivity) throws Http2Exception {
        DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder(sensitivity, false);
        encoder.configuration().maxHeaderTableSize(tableSize);
        if (encoder.configuration().maxHeaderTableSize() != tableSize) {
            throw new IllegalArgumentException("Requested table size " + tableSize + " was clamped to " +
                    encoder.configuration().maxHeaderTableSize() +
                    "; this codec does not support the requested benchmark");
        }
        return encoder;
    }

    private Http2Headers newHeaders(int sequence) {
        Http2Headers result = new DefaultHttp2Headers(false);
        if (scenario == Scenario.STATIC) {
            return result.method("GET").scheme("https").path("/").add("accept-encoding", "gzip, deflate");
        }
        for (int field = 0; field < 4; ++field) {
            int id = sequence * 4 + field;
            // Keep the original 512-entry WORKING_SET and MIXED data unchanged.
            // The additional fixed cardinalities straddle the 16/32/64 KiB capacity knees.
            // 384/768 do not divide the corpus: its wrap is intentionally validated above.
            if (scenario == Scenario.WORKING_SET128) {
                id %= 128;
            } else if (scenario == Scenario.WORKING_SET384) {
                id %= 384;
            } else if (scenario == Scenario.WORKING_SET768) {
                id %= 768;
            } else if (scenario == Scenario.WORKING_SET || scenario == Scenario.MIXED && field != 3) {
                id %= 512;
            }
            AsciiString name = new AsciiString("x-key-" + (scenario == Scenario.CHURN ? id : field));
            AsciiString value;
            if (scenario == Scenario.COLLISION) {
                // AsciiString's case-insensitive hash makes these distinct, legal values collide.
                // All values have equal length, and the corpus exceeds even a 64 KiB table.
                char[] chars = new char[16];
                for (int bit = 0; bit < chars.length; ++bit) {
                    chars[bit] = (id & 1 << bit) == 0 ? 'a' : 'A';
                }
                value = new AsciiString(new String(chars));
                if (value.hashCode() != AsciiString.hashCode("aaaaaaaaaaaaaaaa")) {
                    throw new IllegalStateException("Collision fixture no longer matches the codec hash");
                }
                name = AsciiString.cached("x-collision");
            } else if (scenario == Scenario.HUFFMAN) {
                StringBuilder text = new StringBuilder(600);
                text.append(id).append('-');
                while (text.length() < 600) {
                    text.append('a');
                }
                value = new AsciiString(text);
            } else {
                value = new AsciiString("value-" + (scenario == Scenario.REPEATED ? field : id));
            }
            result.add(name, value);
        }
        return result;
    }

    @Benchmark
    public int encode() throws Http2Exception {
        output.clear();
        encoder.encodeHeaders(3, headers[encodeIndex++ & (CORPUS_SIZE - 1)], output);
        return output.readableBytes();
    }

    /** Diagnostic only: the same fixture iterator, without encoding or changing the primary workload. */
    @Benchmark
    public void traverseHeaders(Blackhole blackhole) {
        for (Map.Entry<CharSequence, CharSequence> header :
                headers[traversalIndex++ & (CORPUS_SIZE - 1)]) {
            blackhole.consume(header.getKey());
            blackhole.consume(header.getValue());
        }
    }

    @Benchmark
    public int decode(Blackhole blackhole) throws Http2Exception {
        ByteBuf input = wire[decodeIndex++ & (CORPUS_SIZE - 1)];
        input.readerIndex(0);
        sink.blackhole = blackhole;
        decoder.decode(3, input, sink, false);
        return input.readerIndex();
    }

    @TearDown
    public void tearDown() {
        if (output != null) {
            output.release();
        }
        if (wire != null) {
            for (ByteBuf block : wire) {
                if (block != null) {
                    block.release();
                }
            }
        }
    }

    private static final class DiscardHeaders extends DefaultHttp2Headers {
        private Blackhole blackhole;

        DiscardHeaders() {
            super(false);
        }

        @Override
        public Http2Headers add(CharSequence name, CharSequence value) {
            blackhole.consume(name);
            blackhole.consume(value);
            return this;
        }
    }
}
