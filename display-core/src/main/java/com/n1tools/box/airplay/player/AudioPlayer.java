package com.n1tools.box.airplay.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaFormat;
import android.util.Log;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;

import java.nio.ByteBuffer;

import com.n1tools.box.airplay.model.PCMPacket;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioPlayer extends Thread {

    private static String TAG = "AudioPlayer";
    private AudioTrack mTrack;
    private int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private int mSampleRate = 44100;
    private boolean isStopThread = false;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final BlockingQueue<PCMPacket> packets = new LinkedBlockingQueue<PCMPacket>(60);

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private MediaCodec mDecoder;
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private volatile boolean isAudioEnabled = true;

    public AudioPlayer() {
        this.mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mAudioFormat,
                AudioTrack.getMinBufferSize(mSampleRate, mChannel, mAudioFormat), AudioTrack.MODE_STREAM);
        this.mTrack.play();
        initDecoder();
    }

    public void setAudioEnabled(boolean enabled) {
        this.isAudioEnabled = enabled;
        if (!enabled) {
            packets.clear();
            if (mTrack != null) {
                mTrack.flush();
            }
        }
    }

    private void initDecoder() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, 44100, 2);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

            try {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                byte[] csd0 = new byte[]{(byte) 0xF8, (byte) 0xE8, (byte) 0x50, (byte) 0x00};
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            } catch (Exception ignored) {}

            try {
                mDecoder = MediaCodec.createByCodecName("OMX.google.aac.decoder");
            } catch (Exception e) {
                mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            }

            mDecoder.configure(format, null, null, 0);
            mDecoder.start();
            mInputBuffers = mDecoder.getInputBuffers();
            mOutputBuffers = mDecoder.getOutputBuffers();
            Log.i(TAG, "Audio decoder successfully configured and started");
        } catch (Exception e) {
            Log.w(TAG, "Audio decoder configuration not supported on this platform: " + e.getMessage());
            mDecoder = null;
        }
    }

    public void addPacker(PCMPacket pcmPacket) {
        if (!isAudioEnabled || pcmPacket == null || pcmPacket.data == null) return;
        // Keep audio latency matched with low-latency video: drop stale packets if backlog > 12
        if (packets.size() > 12) {
            packets.poll();
        }
        if (!packets.offer(pcmPacket)) {
            packets.poll();
            packets.offer(pcmPacket);
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        super.run();
        while (!isStopThread) {
            try {
                PCMPacket packet = packets.take();
                doPlay(packet);
            } catch (InterruptedException e) {
                if (!isStopThread) {
                    Log.e(TAG, "run: take error: ", e);
                }
            }
        }
    }

    private void doPlay(PCMPacket pcmPacket) {

        if (mDecoder != null && mTrack != null) {
//            Log.d(TAG, "doPlay: ");
            decodeAndPlay(pcmPacket.data);
        } else {
            Log.d(TAG, "doPlay mDecoder:" + mDecoder + "--mTrack:" + mTrack);
        }

    }

    private int mNoOutputCounter = 0;
    private void decodeAndPlay(byte[] data) {
        if (!isAudioEnabled || mDecoder == null || mTrack == null || data == null) return;

        // 1. Queue input data using pre-allocated buffers (x86_64 safe)
        int inputIndex = mDecoder.dequeueInputBuffer(5000);
        if (inputIndex >= 0 && mInputBuffers != null && inputIndex < mInputBuffers.length) {
            ByteBuffer buffer = mInputBuffers[inputIndex];
            buffer.clear();
            int writeLen = Math.min(data.length, buffer.remaining());
            buffer.put(data, 0, writeLen);
            mDecoder.queueInputBuffer(inputIndex, 0, writeLen, 0, 0);
        }

        // 2. Drain decoded PCM output
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex = mDecoder.dequeueOutputBuffer(info, 5000);

        if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mOutputBuffers = mDecoder.getOutputBuffers();
        } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mNoOutputCounter++;
            if (mNoOutputCounter > 100) {
                Log.w(TAG, "Audio decoder stalled, reinitializing...");
                reinitDecoder();
                mNoOutputCounter = 0;
            }
        } else {
            mNoOutputCounter = 0;
            while (outputIndex >= 0 && isAudioEnabled) {
                if (mOutputBuffers != null && outputIndex < mOutputBuffers.length) {
                    ByteBuffer buffer = mOutputBuffers[outputIndex];
                    if (buffer != null && info.size > 0) {
                        byte[] pcm = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(pcm, 0, info.size);
                        mTrack.write(pcm, 0, pcm.length);
                    }
                }
                mDecoder.releaseOutputBuffer(outputIndex, false);
                outputIndex = mDecoder.dequeueOutputBuffer(info, 0);
            }
        }
    }

    // 增加重置方法
    private synchronized void reinitDecoder() {
        try {
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
            }
            initDecoder();
        } catch (Exception e) {
            Log.e(TAG, "reinitDecoder failed", e);
        }
    }

    public void stopPlay() {
        isStopThread = true;
        if (mTrack != null) {
            mTrack.flush();
            mTrack.stop();
            mTrack.release();
            packets.clear();
            mTrack = null;
        }

        if (mDecoder != null) {
            Log.d(TAG, "stopPlay mDecoder stop: ");
            try {
                mDecoder.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                mDecoder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mDecoder = null;
            Log.d(TAG, "stopPlay mDecoder stop end");

        }
    }

}
