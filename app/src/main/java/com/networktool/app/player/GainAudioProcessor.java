package com.networktool.app.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * 音量增强（软件增益）。
 *
 * <p>很多 IPTV / 直播源的原始音量本身偏小（广播端做了归一化或本身就录得轻），
 * 播放器音量拉到 100% 依然偏小。这里在 PCM 输出链路里对 16bit 样本做饱和放大，
 * 150% / 200% / 300% 三档可调，超过量程的样本自动截到 16bit 边界，避免爆音破音。
 */
public final class GainAudioProcessor extends BaseAudioProcessor {

    /** 增益倍数，1.0 表示不处理（直通） */
    private volatile float gain = 1f;

    public void setGain(float value) {
        if (value <= 1.001f) {
            this.gain = 1f;
        } else {
            this.gain = Math.min(4f, value);
        }
    }

    public float getGain() {
        return gain;
    }

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // 只处理 16bit 整型 PCM，其余格式交给后面的链路
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining < 2) {
            // 不足一个样本，直接丢弃，避免死循环
            inputBuffer.position(inputBuffer.limit());
            return;
        }
        float currentGain = gain;
        int sampleCount = remaining / 2;

        // 先把样本读进临时数组，再写输出缓冲：
        // 管线可能把同一块缓冲当作输入和输出，直接互相 put 会抛 IllegalArgumentException
        short[] samples = new short[sampleCount];
        ShortBuffer source = inputBuffer.asShortBuffer();
        source.get(samples);
        inputBuffer.position(inputBuffer.position() + sampleCount * 2);

        ByteBuffer outputBuffer = replaceOutputBuffer(sampleCount * 2);
        for (int i = 0; i < sampleCount; i++) {
            int value = samples[i];
            if (currentGain > 1.001f) {
                value = Math.round(value * currentGain);
                if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
                if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
            }
            outputBuffer.putShort((short) value);
        }
        outputBuffer.flip();
    }
}
