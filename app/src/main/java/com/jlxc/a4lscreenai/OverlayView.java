package com.jlxc.a4lscreenai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

public class OverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String statusText = "等待录屏取帧";
    private float fps = 0f;
    private int captureW = 0;
    private int captureH = 0;
    private int screenW = 0;
    private int screenH = 0;

    private final RectF[] boxes = new RectF[] {
            new RectF(0.08f, 0.20f, 0.26f, 0.58f),
            new RectF(0.56f, 0.30f, 0.80f, 0.72f),
            new RectF(0.36f, 0.56f, 0.50f, 0.82f)
    };
    private final String[] labels = new String[] { "行人 DEMO", "车辆 DEMO", "两轮车 DEMO" };

    public OverlayView(Context context) {
        super(context);
        boxPaint.setColor(Color.argb(230, 0, 255, 170));
        boxPaint.setStrokeWidth(5f);
        boxPaint.setStyle(Paint.Style.STROKE);

        linePaint.setColor(Color.argb(180, 255, 255, 255));
        linePaint.setStrokeWidth(2f);
        linePaint.setStyle(Paint.Style.STROKE);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setStyle(Paint.Style.FILL);

        bgPaint.setColor(Color.argb(150, 0, 0, 0));
        bgPaint.setStyle(Paint.Style.FILL);
    }

    public void setStatusText(String s) {
        statusText = s;
        invalidate();
    }

    public void setCaptureInfo(int cw, int ch, int sw, int sh, float currentFps) {
        captureW = cw;
        captureH = ch;
        screenW = sw;
        screenH = sh;
        fps = currentFps;
        invalidate();
    }

    public void setDemoBoxes(long nowMs) {
        // 做一点轻微移动，证明悬浮层是实时刷新的。
        float t = (nowMs % 3000) / 3000f;
        boxes[0].left = 0.08f + 0.03f * t;
        boxes[0].right = 0.26f + 0.03f * t;
        boxes[1].top = 0.30f + 0.03f * t;
        boxes[1].bottom = 0.72f + 0.03f * t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 顶部状态条
        canvas.drawRect(18, 18, Math.min(w - 18, 980), 118, bgPaint);
        String info = statusText + "  |  FPS " + String.format(Locale.US, "%.1f", fps);
        if (captureW > 0) info += "  |  capture " + captureW + "x" + captureH;
        canvas.drawText(info, 36, 78, textPaint);

        // 辅助线：倒车/前视安全区域，可后续改为真实距离区域。
        canvas.drawLine(w * 0.20f, h * 0.92f, w * 0.80f, h * 0.92f, linePaint);
        canvas.drawLine(w * 0.28f, h * 0.75f, w * 0.72f, h * 0.75f, linePaint);
        canvas.drawLine(w * 0.36f, h * 0.58f, w * 0.64f, h * 0.58f, linePaint);

        // 模拟 AI 检测框。下一步接 YOLO/NCNN/TFLite 后，把这里替换成真实检测结果。
        for (int i = 0; i < boxes.length; i++) {
            RectF n = boxes[i];
            RectF r = new RectF(n.left * w, n.top * h, n.right * w, n.bottom * h);
            canvas.drawRect(r, boxPaint);
            float tw = textPaint.measureText(labels[i]);
            canvas.drawRect(r.left, Math.max(0, r.top - 44), r.left + tw + 20, r.top, bgPaint);
            canvas.drawText(labels[i], r.left + 10, r.top - 10, textPaint);
        }
    }
}
