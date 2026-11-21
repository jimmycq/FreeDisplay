package com.n1tools.box.airplay.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

public class PlaySurfaceView extends SurfaceView {

    public static final int SCALE_FIT = 0;              // 比例适应：原画居中，留黑边，无变形
    public static final int SCALE_ZOOM = 1;             // 等比充满：撑满屏幕，等比例放大居中，消黑边，无拉伸变形
    public static final int SCALE_FILL = 2;             // 强制拉伸全屏：强制填满 2048x1536 屏幕
    public static final int SCALE_ORIGINAL = 3;         // 1:1 原生点对点：原始像素点对点显示

    private int mScaleMode = SCALE_FIT;
    private float mWidth = 1920f;
    private float mHeight = 1080f;

    public PlaySurfaceView(Context context) {
        super(context);
    }

    public PlaySurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlaySurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setScaleMode(int mode) {
        if (this.mScaleMode != mode) {
            this.mScaleMode = mode;
            requestLayout();
        }
    }

    public int getScaleMode() {
        return mScaleMode;
    }

    public float getVideoWidth() {
        return mWidth;
    }

    public float getVideoHeight() {
        return mHeight;
    }

    public void setMeasure(final int width, final int height) {
        if (width > 0 && height > 0) {
            if ((int) mWidth == width && (int) mHeight == height) {
                return;
            }
            mWidth = width;
            mHeight = height;
            post(new Runnable() {
                @Override
                public void run() {
                    requestLayout();
                }
            });
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int specWidth = MeasureSpec.getSize(widthMeasureSpec);
        int specHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (specWidth <= 0 || specHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        float videoW = mWidth > 0 ? mWidth : 1920f;
        float videoH = mHeight > 0 ? mHeight : 1080f;

        int width;
        int height;

        if (mScaleMode == SCALE_FILL) {
            // 强制拉伸全屏：填满屏幕尺寸
            width = specWidth;
            height = specHeight;
        } else if (mScaleMode == SCALE_ZOOM) {
            // 等比居中充满：以撑满全屏为准等比放大，消除所有黑边，长宽比完全锁定，绝不拉伸变形！
            float scaleW = (float) specWidth / videoW;
            float scaleH = (float) specHeight / videoH;
            float maxScale = Math.max(scaleW, scaleH);
            width = (int) (videoW * maxScale);
            height = (int) (videoH * maxScale);
        } else if (mScaleMode == SCALE_ORIGINAL) {
            // 1:1 原生像素点对点：视频小于屏幕时点对点居中，大于屏幕时回退为比例适应
            if (videoW <= specWidth && videoH <= specHeight) {
                width = (int) videoW;
                height = (int) videoH;
            } else if (specWidth * videoH < specHeight * videoW) {
                width = specWidth;
                height = (int) (specWidth * videoH / videoW);
            } else {
                height = specHeight;
                width = (int) (specHeight * videoW / videoH);
            }
        } else {
            // SCALE_FIT: 保持比例居中适应（留黑边，无变形）
            if (specWidth * videoH < specHeight * videoW) {
                width = specWidth;
                height = (int) (specWidth * videoH / videoW);
            } else {
                height = specHeight;
                width = (int) (specHeight * videoW / videoH);
            }
        }

        setMeasuredDimension(width, height);
    }
}
