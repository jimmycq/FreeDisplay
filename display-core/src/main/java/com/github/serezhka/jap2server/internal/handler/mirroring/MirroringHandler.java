package com.github.serezhka.jap2server.internal.handler.mirroring;

import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2server.AirplayDataConsumer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MirroringHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(MirroringHandler.class);

    private final ByteBuf headerBuf = ByteBufAllocator.DEFAULT.ioBuffer(128, 128);
    private final AirPlay airPlay;
    private final AirplayDataConsumer dataConsumer;
    private long lastUnhandledLogMs = 0L;
    private long videoPacketCount = 0L;
    private long configPacketCount = 0L;
    private long perfPacketCount = 0L;

    private MirroringHeader header;
    private ByteBuf payload;

    public MirroringHandler(AirPlay airPlay, AirplayDataConsumer dataConsumer) {
        this.airPlay = airPlay;
        this.dataConsumer = dataConsumer;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("AirPlay video mirroring channel inactive (disconnected)");
        if (dataConsumer != null) {
            dataConsumer.onDisconnected();
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        while (msg.isReadable()) {

            if (header == null) {
                msg.readBytes(headerBuf, Math.min(headerBuf.writableBytes(), msg.readableBytes()));
                if (headerBuf.writableBytes() == 0) {
                    header = new MirroringHeader(headerBuf);
                    headerBuf.clear();
                }
            }

            if (header != null && msg.readableBytes() > 0) {

                if (payload == null || payload.writableBytes() == 0) {
                    payload = ctx.alloc().directBuffer(header.getPayloadSize(), header.getPayloadSize());
                }

                msg.readBytes(payload, Math.min(payload.writableBytes(), msg.readableBytes()));

                if (payload.writableBytes() == 0) {

                    byte[] payloadBytes = new byte[header.getPayloadSize()];
                    payload.readBytes(payloadBytes);

                    try {
                        short payloadType = header.getPayloadType();
                        if (payloadType == 0) {
                            videoPacketCount++;
                            if (videoPacketCount <= 5 || videoPacketCount % 300 == 0) {
                                android.util.Log.i("AirPlayMirror", "Video payload #" + videoPacketCount
                                        + " size=" + payloadBytes.length
                                        + " option=" + header.getPayloadOption());
                            }
                            airPlay.decryptVideo(payloadBytes);
                            processVideo(payloadBytes);
                        } else if (payloadType == 1) {
                            configPacketCount++;
                            int width = header.getWidth();
                            int height = header.getHeight();
                            android.util.Log.i("AirPlayMirror", "Stream config payload received: " + width + "x" + height
                                    + " size=" + payloadBytes.length
                                    + " option=" + header.getPayloadOption()
                                    + " count=" + configPacketCount);
                            dataConsumer.onVideoSize(width, height);
                            processSPSPPS(payloadBytes);
                        } else if (payloadType == 5) {
                            perfPacketCount++;
                            if (perfPacketCount <= 3 || perfPacketCount % 60 == 0) {
                                android.util.Log.i("AirPlayMirror", "Performance payload size=" + payloadBytes.length
                                        + " count=" + perfPacketCount + " (ignored)");
                            }
                        } else {
                            long now = System.currentTimeMillis();
                            if (now - lastUnhandledLogMs > 5000L) {
                                lastUnhandledLogMs = now;
                                android.util.Log.d("AirPlayMirror", "Unhandled payload type: " + payloadType
                                        + " size=" + payloadBytes.length);
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("AirPlayMirror", "Error processing video payload", e);
                    }

                    payload.release();
                    payload = null;
                    header = null;
                }
            }
        }
    }

    private void processVideo(byte[] payload) {
        int offset = 0;
        int naluCount = 0;
        while (offset + 4 <= payload.length) {
            int nc_len = ((payload[offset] & 0xFF) << 24)
                       | ((payload[offset + 1] & 0xFF) << 16)
                       | ((payload[offset + 2] & 0xFF) << 8)
                       | (payload[offset + 3] & 0xFF);
            if (nc_len > 0 && offset + 4 + nc_len <= payload.length) {
                payload[offset] = 0;
                payload[offset + 1] = 0;
                payload[offset + 2] = 0;
                payload[offset + 3] = 1;
                offset += 4 + nc_len;
                naluCount++;
            } else {
                break;
            }
        }

        if (naluCount > 0) {
            dataConsumer.onVideo(payload);
        } else {
            android.util.Log.w("AirPlayMirror", "processVideo: zero NALUs converted in payload len=" + payload.length);
        }
    }

    private void processSPSPPS(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length < 10) {
            android.util.Log.e("AirPlayMirror", "processSPSPPS: payload too short (" + (payloadBytes == null ? 0 : payloadBytes.length) + ")");
            return;
        }
        try {
            int spsLen = ((payloadBytes[6] & 0xFF) << 8) | (payloadBytes[7] & 0xFF);
            if (8 + spsLen >= payloadBytes.length) {
                android.util.Log.e("AirPlayMirror", "processSPSPPS: invalid spsLen " + spsLen);
                return;
            }
            byte[] sequenceParameterSet = new byte[spsLen];
            System.arraycopy(payloadBytes, 8, sequenceParameterSet, 0, spsLen);

            int ppsIndex = 8 + spsLen + 1; // skip 1 byte pps count
            if (ppsIndex + 2 > payloadBytes.length) {
                android.util.Log.e("AirPlayMirror", "processSPSPPS: no pps data at " + ppsIndex);
                return;
            }
            int ppsLen = ((payloadBytes[ppsIndex] & 0xFF) << 8) | (payloadBytes[ppsIndex + 1] & 0xFF);
            if (ppsIndex + 2 + ppsLen > payloadBytes.length) {
                android.util.Log.e("AirPlayMirror", "processSPSPPS: invalid ppsLen " + ppsLen);
                return;
            }
            byte[] pictureParameterSet = new byte[ppsLen];
            System.arraycopy(payloadBytes, ppsIndex + 2, pictureParameterSet, 0, ppsLen);

            int spsPpsLen = spsLen + ppsLen + 8;
            android.util.Log.i("AirPlayMirror", "Extracted SPS (len=" + spsLen + ") & PPS (len=" + ppsLen + "), total=" + spsPpsLen);
            byte[] spsPps = new byte[spsPpsLen];
            spsPps[0] = 0;
            spsPps[1] = 0;
            spsPps[2] = 0;
            spsPps[3] = 1;
            System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen);
            spsPps[spsLen + 4] = 0;
            spsPps[spsLen + 5] = 0;
            spsPps[spsLen + 6] = 0;
            spsPps[spsLen + 7] = 1;
            System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen);

            dataConsumer.onVideo(spsPps);
        } catch (Exception e) {
            android.util.Log.e("AirPlayMirror", "Error parsing SPS/PPS", e);
        }
    }
}
