package com.n1tools.box.airplay.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.n1tools.box.airplay.model.NALPacket;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class VideoPlayer extends Thread {
    private static final String TAG = "VideoPlayer";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    // Buffer queue sizing for real-time low-latency casting:
    // 120 frames max (~2s headroom). Never drop P-frames without an IDR recovery point!
    private static final int MAX_QUEUE_CAPACITY = 120;

    public interface OnVideoSizeChangedListener {
        void onVideoSize(int width, int height);
    }

    public interface OnFirstFrameRenderedListener {
        void onFirstFrameRendered();
    }

    private final Surface mSurface;
    private final int mInitialWidth;
    private final int mInitialHeight;

    private MediaCodec mDecoder = null;
    private final BlockingQueue<NALPacket> mQueue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
    private volatile boolean isRunning = false;
    private volatile boolean mHasKeyFrame = false;
    private volatile boolean mHasRenderedFirstFrame = false;
    private volatile long mConfigGeneration = 0;
    private OnVideoSizeChangedListener mSizeListener = null;
    private OnFirstFrameRenderedListener mFirstFrameRenderedListener = null;

    public VideoPlayer(Surface surface, int width, int height) {
        super("VideoPlayerThread");
        this.mSurface = surface;
        // Default to standard 1080p if dimensions are invalid or in portrait
        this.mInitialWidth = (width > 0 && width >= height) ? width : 1920;
        this.mInitialHeight = (height > 0 && width >= height) ? height : 1080;
    }

    public void setOnFirstFrameRenderedListener(OnFirstFrameRenderedListener listener) {
        this.mFirstFrameRenderedListener = listener;
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        this.mSizeListener = listener;
    }

    public void addPacker(NALPacket packet) {
        if (packet == null || packet.nalData == null) return;

        // CRITICAL: In H.264 video streams (especially AirPlay), dropping P-frames
        // without an IDR keyframe causes Intel VED hardware decoder to permanently fail
        // with DECODE_NO_REFERENCE. Never blindly purge P-frames!
        if (!mQueue.offer(packet)) {
            // Queue full: only purge if an actual IDR keyframe is waiting ahead
            purgeStaleFrames();
            if (!mQueue.offer(packet)) {
                try {
                    mQueue.poll(5, TimeUnit.MILLISECONDS);
                    mQueue.offer(packet);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private synchronized void purgeStaleFrames() {
        boolean hasIdrAhead = false;
        for (NALPacket p : mQueue) {
            if (isIdrFrame(p)) {
                hasIdrAhead = true;
                break;
            }
        }
        if (!hasIdrAhead) {
            // Cannot purge: all P-frames are needed for reference chain
            return;
        }

        int dropped = 0;
        while (!mQueue.isEmpty()) {
            NALPacket candidate = mQueue.peek();
            if (candidate != null && isIdrFrame(candidate)) {
                break;
            }
            mQueue.poll();
            dropped++;
        }
        if (dropped > 0) {
            Log.w(TAG, "Adaptive catchup: purged " + dropped + " stale frames before IDR frame (queue=" + mQueue.size() + ")");
        }
    }

    private synchronized boolean initDecoder() {
        MediaCodec decoder = null;
        try {
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mInitialWidth, mInitialHeight);

            // 2MB input buffer allocation for 1080p60 high-bitrate I-frames
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);

            // Intel Atom Z3580 VED hardware decoder low-latency hints
            try {
                format.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);
                // Low latency and realtime priority hints (supported on Lollipop+ vendor OMX decoders)
                format.setInteger("priority", 0);
                format.setInteger("low-latency", 1);
            } catch (Exception ignored) {}

            if (mSurface != null && mSurface.isValid()) {
                decoder.configure(format, mSurface, null, 0);
                decoder.start();
                mDecoder = decoder;
                Log.i(TAG, "Intel VED Video Decoder configured and started (" + mInitialWidth + "x" + mInitialHeight + ")");
                return true;
            } else {
                Log.e(TAG, "Surface is invalid, cannot configure decoder");
                safeRelease(decoder);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize decoder", e);
            safeRelease(decoder);
            return false;
        }
    }

    @Override
    public void run() {
        // Boost decoding thread priority on Linux CFS scheduler for minimum jitter
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        isRunning = true;

        if (!initDecoder() || mDecoder == null) {
            Log.e(TAG, "Decoder initialization failed, stopping thread");
            return;
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        ByteBuffer[] inputBuffers = null;
        ByteBuffer[] outputBuffers = null;
        synchronized (this) {
            if (mDecoder == null) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                inputBuffers = mDecoder.getInputBuffers();
                outputBuffers = mDecoder.getOutputBuffers();
            }
        }

        NALPacket pendingPacket = null;
        long generation = mConfigGeneration;

        while (isRunning) {
            try {
                // 1. Fetch next packet
                if (pendingPacket == null) {
                    pendingPacket = mQueue.poll(5, TimeUnit.MILLISECONDS);
                }

                // All MediaCodec calls are serialized with reset()/releaseDecoder().
                synchronized (this) {
                    if (generation != mConfigGeneration) {
                        generation = mConfigGeneration;
                        pendingPacket = null;
                    }
                    if (mDecoder == null) {
                        continue;
                    }

                    if (pendingPacket != null && pendingPacket.nalData != null) {
                        boolean isConfig = isConfigPacket(pendingPacket);
                        boolean isIdr = isIdrFrame(pendingPacket);

                        if (!mHasKeyFrame) {
                            if (isConfig) {
                                // SPS/PPS config packet received. Feed it to configure decoder,
                                // but do NOT unlock decoder for P-frames yet until the actual IDR slice arrives!
                                Log.i(TAG, "SPS/PPS Config received, configuring decoder format...");
                            } else if (isIdr) {
                                mHasKeyFrame = true;
                                Log.i(TAG, "First IDR KeyFrame received! Unlocking hardware decoder.");
                            } else {
                                // Drop non-keyframe P-frames until first IDR arrives,
                                // completely preventing Intel VED DECODE_NO_REFERENCE / black screen.
                                pendingPacket = null;
                                continue;
                            }
                        }

                        int flags = 0;
                        if (isConfig) {
                            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
                        } else if (isIdr) {
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                            mHasKeyFrame = true;
                        }

                        // Query decoder input buffer with timeout (5000 us = 5 ms)
                        int inputBufferIndex = mDecoder.dequeueInputBuffer(5000);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                            } else {
                                inputBuffer = (inputBuffers != null) ? inputBuffers[inputBufferIndex] : null;
                            }

                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                int offset = pendingPacket.offset;
                                int len = pendingPacket.length > 0 ? pendingPacket.length : pendingPacket.nalData.length;
                                inputBuffer.put(pendingPacket.nalData, offset, len);
                                mDecoder.queueInputBuffer(inputBufferIndex, 0, len, pendingPacket.pts, flags);
                            }
                            // Packet successfully queued into hardware decoder
                            pendingPacket = null;
                        }
                    }

                    // 2. Drain output buffers once frames have been fed to decoder
                    if (mHasKeyFrame) {
                        drainOutputBuffers(bufferInfo);
                    }
                }

            } catch (IllegalStateException e) {
                if (isRunning) {
                    Log.e(TAG, "Fatal decoder state error, reinitializing decoder...", e);
                    releaseDecoder();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {}
                    if (isRunning) {
                        if (mSurface != null && mSurface.isValid()) {
                            initDecoder();
                            mHasKeyFrame = false;
                            mHasRenderedFirstFrame = false;
                        } else {
                            Log.w(TAG, "Surface is temporarily invalid during layout change, waiting for recovery...");
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Error in video decode loop", e);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {}
                }
            }
        }

        releaseDecoder();
    }

    private synchronized void drainOutputBuffers(MediaCodec.BufferInfo bufferInfo) {
        if (mDecoder == null) return;

        int outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
        while (outputBufferIndex >= 0) {
            if (!mHasRenderedFirstFrame) {
                mHasRenderedFirstFrame = true;
                Log.i(TAG, "SUCCESS! First video frame rendered to display! (" + bufferInfo.size + " bytes)");
                if (mFirstFrameRenderedListener != null) {
                    mFirstFrameRenderedListener.onFirstFrameRendered();
                }
            }
            // Direct zero-copy hardware render to SurfaceView
            mDecoder.releaseOutputBuffer(outputBufferIndex, true);
            outputBufferIndex = mDecoder.dequeueOutputBuffer(bufferInfo, 0);
        }

        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            try {
                MediaFormat newFormat = mDecoder.getOutputFormat();
                Log.i(TAG, "Decoder output format changed: " + newFormat);
                if (mSizeListener != null && newFormat != null) {
                    int w = newFormat.containsKey(MediaFormat.KEY_WIDTH) ? newFormat.getInteger(MediaFormat.KEY_WIDTH) : 1920;
                    int h = newFormat.containsKey(MediaFormat.KEY_HEIGHT) ? newFormat.getInteger(MediaFormat.KEY_HEIGHT) : 1080;
                    if (newFormat.containsKey("crop-left") && newFormat.containsKey("crop-right")) {
                        int cropW = newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1;
                        // Reject bogus Intel Moorefield OMX default 1280x800 crop on 1080p stream
                        if (cropW > 0 && !(w >= 1920 && cropW <= 1280)) {
                            w = cropW;
                        }
                    }
                    if (newFormat.containsKey("crop-top") && newFormat.containsKey("crop-bottom")) {
                        int cropH = newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1;
                        if (cropH > 0 && !(h >= 1080 && cropH <= 800)) {
                            h = cropH;
                        }
                    }
                    Log.i(TAG, "Sanitized video stream dimensions: " + w + "x" + h);
                    mSizeListener.onVideoSize(w, h);
                }
            } catch (Exception ignored) {}
        }
    }

    private synchronized void releaseDecoder() {
        MediaCodec decoder = mDecoder;
        mDecoder = null;
        if (decoder != null) {
            safeRelease(decoder);
            Log.i(TAG, "Video Decoder stopped and released");
        }
        mQueue.clear();
    }

    private synchronized void safeRelease(MediaCodec decoder) {
        if (decoder == null) return;
        try {
            decoder.stop();
        } catch (Exception e) {
            Log.w(TAG, "MediaCodec stop failed: " + e.getMessage());
        }
        try {
            decoder.release();
        } catch (Exception e) {
            Log.w(TAG, "MediaCodec release failed: " + e.getMessage());
        }
    }

    private boolean isKeyFrameOrConfig(NALPacket packet) {
        if (packet == null || packet.nalData == null) return false;
        byte[] data = packet.nalData;
        int start = packet.offset;
        int end = packet.length > 0 ? start + packet.length : data.length;
        if (end - start < 5) return false;

        // Check for Annex B start codes: 00 00 01 or 00 00 00 01
        for (int i = start; i < end - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    int type = data[i + 3] & 0x1F;
                    if (type == 5 || type == 7 || type == 8) { // IDR, SPS, PPS
                        return true;
                    }
                } else if (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < end) {
                    int type = data[i + 4] & 0x1F;
                    if (type == 5 || type == 7 || type == 8) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isIdrFrame(NALPacket packet) {
        if (packet == null || packet.nalData == null) return false;
        byte[] data = packet.nalData;
        int start = packet.offset;
        int end = packet.length > 0 ? start + packet.length : data.length;
        for (int i = start; i < end - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                int type = -1;
                if (data[i + 2] == 1) {
                    type = data[i + 3] & 0x1F;
                } else if (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < end) {
                    type = data[i + 4] & 0x1F;
                }
                if (type == 5) return true;
            }
        }
        return false;
    }

    private boolean isConfigPacket(NALPacket packet) {
        if (packet == null || packet.nalData == null) return false;
        byte[] data = packet.nalData;
        int start = packet.offset;
        int end = packet.length > 0 ? start + packet.length : data.length;
        boolean hasConfig = false;
        boolean hasSlice = false;
        for (int i = start; i < end - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                int type = -1;
                if (data[i + 2] == 1) {
                    type = data[i + 3] & 0x1F;
                } else if (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < end) {
                    type = data[i + 4] & 0x1F;
                }
                if (type == 7 || type == 8) hasConfig = true;
                if (type == 1 || type == 5) hasSlice = true;
            }
        }
        return hasConfig && !hasSlice;
    }

    public synchronized void reset() {
        Log.i(TAG, "Resetting VideoPlayer: fully re-creating hardware decoder for new stream...");
        mConfigGeneration++;
        mQueue.clear();
        mHasKeyFrame = false;
        mHasRenderedFirstFrame = false;
        try {
            releaseDecoder();
            if (mSurface != null && mSurface.isValid()) {
                initDecoder();
                Log.i(TAG, "Hardware decoder cleanly re-created on reset");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error re-initializing decoder on reset", e);
        }
    }

    public void stopVideoPlay() {
        isRunning = false;
        interrupt();
        // Release synchronously so a discarded player can never be finalized with a live decoder.
        synchronized (this) {
            releaseDecoder();
        }
    }
}
