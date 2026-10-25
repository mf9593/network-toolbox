package com.networktool.app.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.Decoder;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoderOutputBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;


/**
 * MP2（MPEG-1/2 Audio Layer I·II·III）纯 Java 软件解码器。
 *
 * <p>Android 系统 MediaCodec 只自带 MP3（Layer III）解码器，而多数电视直播流 / 监控流
 * 的音轨是 MPEG-1 Layer II（{@code audio/mpeg-L2}），系统无法解码。
 * 本类移植 JavaMP3（MIT 许可，https://github.com/delthas/JavaMP3）的纯 Java 解码内核，
 * 以 ExoPlayer {@link Decoder} 的形式接入 {@code DecoderAudioRenderer} 的音频链路。
 *
 * <p>实现要点：
 * <ul>
 *   <li>把连续字节流切成完整 MPEG 音频帧，逐帧解码，输出 16bit 小端交错 PCM；</li>
 *   <li>不完整的尾帧留到下一次调用，不丢帧；</li>
 *   <li>解码在同一线程内同步完成（单帧开销很小），无需额外解码线程；</li>
 *   <li>输入/输出缓冲池化复用，降低 GC 压力。</li>
 * </ul>
 */
final class Mp2AudioDecoder
        implements Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int DEFAULT_CHANNEL_COUNT = 2;
    /** 未拼成完整帧的字节上限，超出说明流异常，直接丢弃防止无限增长 */
    private static final int MAX_CARRY_BYTES = 512 * 1024;
    private static final int INPUT_BUFFER_POOL_SIZE = 8;

    private static final int[] BITRATE_MPEG1_LAYER1 =
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448};
    private static final int[] BITRATE_MPEG1_LAYER2 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BITRATE_MPEG1_LAYER3 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATE_MPEG2_LAYER1 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256};
    private static final int[] BITRATE_MPEG2_LAYER23 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SAMPLE_RATES = {44100, 48000, 32000};

    private final ArrayDeque<DecoderInputBuffer> freeInputs = new ArrayDeque<>();
    private final ArrayDeque<SimpleDecoderOutputBuffer> outputQueue = new ArrayDeque<>();
    private final ArrayDeque<SimpleDecoderOutputBuffer> freeOutputs = new ArrayDeque<>();

    private fr.delthas.javamp3.Decoder.SoundData soundData = fr.delthas.javamp3.Decoder.newSoundData();

    /** 未消费的原始字节（可能含半帧） */
    private byte[] carry = new byte[32 * 1024];
    private int carryLength;

    /** 单次输出缓冲的 PCM 累加区 */
    private byte[] pcmBuffer = new byte[64 * 1024];
    private int pcmLength;

    private int sampleRate;
    private int channelCount;

    private DecoderInputBuffer dequeuedInput;
    private long decodedFrames;
    private long failedFrames;
    private boolean released;

    Mp2AudioDecoder(int sampleRate, int channelCount) {
        this.sampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
        this.channelCount = channelCount > 0 ? channelCount : DEFAULT_CHANNEL_COUNT;
        for (int i = 0; i < INPUT_BUFFER_POOL_SIZE; i++) {
            freeInputs.addLast(newInputBuffer());
        }
    }

    private static DecoderInputBuffer newInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    }

    int getSampleRate() {
        return sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
    }

    int getChannelCount() {
        return channelCount > 0 ? channelCount : DEFAULT_CHANNEL_COUNT;
    }

    long getDecodedFrameCount() {
        return decodedFrames;
    }

    long getFailedFrameCount() {
        return failedFrames;
    }

    @Override
    public String getName() {
        return "Mp2AudioDecoder(JavaMP3)";
    }

    /* ==================== Decoder 接口 ==================== */

    @Override
    public DecoderInputBuffer dequeueInputBuffer() {
        if (released || dequeuedInput != null) {
            return null;
        }
        DecoderInputBuffer buffer = freeInputs.pollFirst();
        if (buffer == null) {
            return null;
        }
        buffer.clear();
        dequeuedInput = buffer;
        return buffer;
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws DecoderException {
        if (inputBuffer == null) {
            return;
        }
        if (dequeuedInput != inputBuffer) {
            // 非本解码器发出的输入缓冲，直接忽略
            return;
        }
        dequeuedInput = null;

        try {
            if (inputBuffer.isEndOfStream()) {
                // 结束标记：输出一个带 EOS 标记的空缓冲
                SimpleDecoderOutputBuffer eos = new SimpleDecoderOutputBuffer(buffer -> { });
                eos.init(inputBuffer.timeUs, 0);
                eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                outputQueue.addLast(eos);
                return;
            }

            long timeUs = inputBuffer.timeUs == C.TIME_UNSET ? 0 : inputBuffer.timeUs;

            // 1. 本次输入追加到 carry
            ByteBuffer input = inputBuffer.data;
            if (input != null) {
                int size = input.remaining();
                if (size > 0) {
                    if (carryLength + size > MAX_CARRY_BYTES) {
                        carryLength = 0; // 异常膨胀，重新同步
                    }
                    ensureCarryCapacity(carryLength + size);
                    input.get(carry, carryLength, size);
                    carryLength += size;
                }
            }

            // 2. 解码所有已完整的帧
            int produced = decodeAvailableFrames();

            // 3. 有 PCM 才产出输出缓冲
            if (produced > 0) {
                SimpleDecoderOutputBuffer output = freeOutputs.pollFirst();
                if (output == null) {
                    output = new SimpleDecoderOutputBuffer(this::recycleOutput);
                }
                ByteBuffer destination = output.init(timeUs, produced);
                destination.put(pcmBuffer, 0, produced);
                destination.flip();
                outputQueue.addLast(output);
            }
        } finally {
            // 输入缓冲立即回收复用
            inputBuffer.clear();
            freeInputs.addLast(inputBuffer);
        }
    }

    @Override
    public SimpleDecoderOutputBuffer dequeueOutputBuffer() {
        SimpleDecoderOutputBuffer output = outputQueue.pollFirst();
        if (output != null && output.isEndOfStream()) {
            // EOS 缓冲只出现一次，直接弹出返回，由渲染器释放
            return output;
        }
        return output;
    }

    @Override
    public void flush() {
        while (!outputQueue.isEmpty()) {
            SimpleDecoderOutputBuffer buffer = outputQueue.pollFirst();
            if (buffer != null && !buffer.isEndOfStream()) {
                recycleOutput(buffer);
            }
        }
        if (dequeuedInput != null) {
            dequeuedInput.clear();
            freeInputs.addLast(dequeuedInput);
            dequeuedInput = null;
        }
        carryLength = 0;
        pcmLength = 0;
    }

    @Override
    public void release() {
        released = true;
        flush();
        freeInputs.clear();
        freeOutputs.clear();
        soundData = fr.delthas.javamp3.Decoder.newSoundData();
    }

    private void recycleOutput(SimpleDecoderOutputBuffer buffer) {
        if (buffer == null || buffer.isEndOfStream()) {
            return;
        }
        if (freeOutputs.size() < 16) {
            freeOutputs.addLast(buffer);
        }
    }

    /* ==================== 帧切分与解码 ==================== */

    /**
     * 尽可能解码 carry 里的完整帧，结果追加到 {@link #pcmBuffer}。
     *
     * @return 本次产出的 PCM 字节数
     */
    private int decodeAvailableFrames() {
        pcmLength = 0;
        int pos = 0;
        while (true) {
            int sync = findSyncWord(carry, pos, carryLength);
            if (sync < 0) {
                pos = carryLength; // 无同步字，全部丢弃
                break;
            }
            pos = sync;

            int available = carryLength - pos;
            if (available < 4) {
                break; // 帧头不全
            }

            int frameLength = frameLengthAt(carry, pos);
            if (frameLength <= 0) {
                pos++; // 非法帧头，跳过 1 字节继续找同步
                continue;
            }
            if (available < frameLength) {
                break; // 整帧未到齐
            }

            parseFrameInfo(carry, pos);

            boolean ok;
            try {
                ok = fr.delthas.javamp3.Decoder.decodeFrameFromBytes(soundData, carry, pos, frameLength);
            } catch (IOException | RuntimeException e) {
                ok = false;
            }

            if (ok) {
                byte[] pcm = soundData.getSamplesBuffer();
                if (pcm != null && pcm.length > 0) {
                    ensurePcmCapacity(pcmLength + pcm.length);
                    System.arraycopy(pcm, 0, pcmBuffer, pcmLength, pcm.length);
                    pcmLength += pcm.length;
                }
                decodedFrames++;
            } else {
                failedFrames++;
            }
            pos += frameLength;
        }

        // 保留未消费的尾部数据
        if (pos > 0) {
            int remain = carryLength - pos;
            if (remain > 0) {
                System.arraycopy(carry, pos, carry, 0, remain);
            }
            carryLength = remain;
        }
        return pcmLength;
    }

    /** 从帧头解析采样率与声道数（每帧刷新，兼容流中途切换参数） */
    private void parseFrameInfo(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return;
        }
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;
        int versionBits = (b1 >> 3) & 0x03;
        int srIndex = (b2 >> 2) & 0x03;
        if (srIndex == 3 || versionBits == 1) {
            return;
        }
        int rate = SAMPLE_RATES[srIndex];
        if (versionBits == 2) {
            rate /= 2;
        } else if (versionBits == 0) {
            rate /= 4;
        }
        if (rate > 0) {
            sampleRate = rate;
        }
        int mode = (b3 >> 6) & 0x03;
        channelCount = (mode == 0x03) ? 1 : 2;
    }

    /** 计算帧长度（字节），非法帧头返回 -1 */
    static int frameLengthAt(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return -1;
        }
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int versionBits = (b1 >> 3) & 0x03; // 3=MPEG1 2=MPEG2 0=MPEG2.5 1=非法
        int layerBits = (b1 >> 1) & 0x03;   // 3=LayerI 2=LayerII 1=LayerIII 0=非法
        if (versionBits == 1 || layerBits == 0) {
            return -1;
        }
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int srIndex = (b2 >> 2) & 0x03;
        if (bitrateIndex == 0 || bitrateIndex == 15 || srIndex == 3) {
            return -1;
        }
        int padding = (b2 >> 1) & 0x01;
        boolean mpeg1 = (versionBits == 3);

        int[] table;
        if (layerBits == 3) {
            table = mpeg1 ? BITRATE_MPEG1_LAYER1 : BITRATE_MPEG2_LAYER1;
        } else if (layerBits == 2) {
            table = mpeg1 ? BITRATE_MPEG1_LAYER2 : BITRATE_MPEG2_LAYER23;
        } else {
            table = mpeg1 ? BITRATE_MPEG1_LAYER3 : BITRATE_MPEG2_LAYER23;
        }
        int bitrate = table[bitrateIndex] * 1000;

        int sampleRate = SAMPLE_RATES[srIndex];
        if (versionBits == 2) {
            sampleRate /= 2;
        } else if (versionBits == 0) {
            sampleRate /= 4;
        }
        if (sampleRate <= 0 || bitrate <= 0) {
            return -1;
        }

        if (layerBits == 3) { // Layer I
            return (12 * bitrate / sampleRate + padding) * 4;
        }
        int coefficient = (!mpeg1 && layerBits == 1) ? 72 : 144;
        return coefficient * bitrate / sampleRate + padding;
    }

    /** 在 [from, to) 区间内查找 11 位帧同步字 */
    private static int findSyncWord(byte[] data, int from, int to) {
        for (int i = from; i + 1 < to; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCarryCapacity(int required) {
        if (required <= carry.length) {
            return;
        }
        int newSize = carry.length;
        while (newSize < required) {
            newSize <<= 1;
        }
        byte[] grown = new byte[newSize];
        System.arraycopy(carry, 0, grown, 0, carryLength);
        carry = grown;
    }

    private void ensurePcmCapacity(int required) {
        if (required <= pcmBuffer.length) {
            return;
        }
        int newSize = pcmBuffer.length;
        while (newSize < required) {
            newSize <<= 1;
        }
        byte[] grown = new byte[newSize];
        System.arraycopy(pcmBuffer, 0, grown, 0, pcmLength);
        pcmBuffer = grown;
    }
}
