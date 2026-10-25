package com.networktool.app.player;

import android.os.Handler;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DecoderAudioRenderer;
import com.google.android.exoplayer2.decoder.CryptoConfig;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

/**
 * MP2（MPEG-1 Layer II）音频渲染器。
 *
 * <p>系统 MediaCodec 没有 MP2 解码器（AOSP 的 OMX.google.mp3.decoder 只支持 Layer III），
 * 因此这里通过 {@link Mp2AudioDecoder} 做纯 Java 软解，再交给 {@link AudioSink} 播放。
 * 只接管 {@code audio/mpeg-L2}，其余音频格式继续由系统 MediaCodec 渲染器处理。
 */
public final class Mp2AudioRenderer extends DecoderAudioRenderer<Mp2AudioDecoder> {

    private Format inputFormat;
    private Mp2AudioDecoder decoderRef;
    private boolean loggedFirstPcm;

    public Mp2AudioRenderer(Handler eventHandler, AudioRendererEventListener eventListener,
                            AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "Mp2AudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        String mimeType = format.sampleMimeType;
        if (!MimeTypes.AUDIO_MPEG_L2.equals(mimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        int channels = format.channelCount > 0 ? format.channelCount : 2;
        int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
        Format pcmFormat = Util.getPcmFormat(C.ENCODING_PCM_16BIT, channels, sampleRate);
        if (!sinkSupportsFormat(pcmFormat)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected Mp2AudioDecoder createDecoder(Format format, CryptoConfig cryptoConfig)
            throws DecoderException {
        inputFormat = format;
        int channels = format.channelCount > 0 ? format.channelCount : 2;
        int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
        RtspPlayer.log("MP2 软解器已创建: " + sampleRate + "Hz " + channels + "声道");
        decoderRef = new Mp2AudioDecoder(sampleRate, channels);
        return decoderRef;
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer inputBuffer) {
        super.onQueueInputBuffer(inputBuffer);
        if (!loggedFirstPcm && decoderRef != null && decoderRef.getDecodedFrameCount() > 0) {
            loggedFirstPcm = true;
            RtspPlayer.log("MP2 软解输出 PCM: 已解码 " + decoderRef.getDecodedFrameCount()
                    + " 帧, 失败 " + decoderRef.getFailedFrameCount()
                    + " 帧, " + decoderRef.getSampleRate() + "Hz "
                    + decoderRef.getChannelCount() + "声道");
        }
    }

    @Override
    protected Format getOutputFormat(Mp2AudioDecoder decoder) {
        int channels = decoder.getChannelCount();
        int sampleRate = decoder.getSampleRate();
        if (channels <= 0 && inputFormat != null) {
            channels = inputFormat.channelCount;
        }
        if (sampleRate <= 0 && inputFormat != null) {
            sampleRate = inputFormat.sampleRate;
        }
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(channels > 0 ? channels : 2)
                .setSampleRate(sampleRate > 0 ? sampleRate : 48000)
                .build();
    }
}
