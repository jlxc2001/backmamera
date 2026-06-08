package com.jlxc.a4lscreenai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "screen_ai_capture";
    private static final int NOTIFICATION_ID = 52001;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private OverlayView overlayView;

    private int captureWidth;
    private int captureHeight;
    private int screenWidth;
    private int screenHeight;
    private int densityDpi;

    private long lastProcessMs = 0;
    private long lastSaveMs = 0;
    private long frameCount = 0;
    private long fpsStartMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("录屏取帧服务运行中"));
        captureThread = new HandlerThread("ScreenCaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        fpsStartMs = System.currentTimeMillis();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == -1 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startOverlay();
        startProjection(resultCode, resultData);
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                updateOverlayStatus("MediaProjection 获取失败");
                stopSelf();
                return;
            }

            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            screenWidth = dm.widthPixels;
            screenHeight = dm.heightPixels;
            densityDpi = dm.densityDpi;

            // 车机屏幕是 2560x720。AI 不需要满分辨率，先抓半分辨率降低压力。
            captureWidth = Math.max(640, screenWidth / 2);
            captureHeight = Math.max(180, screenHeight / 2);

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    handleImage(image);
                } catch (Throwable t) {
                    updateOverlayStatus("取帧错误: " + t.getClass().getSimpleName());
                } finally {
                    if (image != null) image.close();
                }
            }, captureHandler);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "A4LScreenCapture",
                    captureWidth,
                    captureHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );

            updateOverlayStatus("录屏取帧中 " + captureWidth + "x" + captureHeight);
        } catch (Throwable t) {
            updateOverlayStatus("启动录屏失败: " + t.getMessage());
            stopSelf();
        }
    }

    private void handleImage(Image image) {
        long now = System.currentTimeMillis();
        // 控制处理频率，先按约 5 FPS 走，后续接 AI 也建议 5-10 FPS。
        if (now - lastProcessMs < 180) return;
        lastProcessMs = now;
        frameCount++;

        float fps = 0f;
        long elapsed = now - fpsStartMs;
        if (elapsed > 0) fps = frameCount * 1000f / elapsed;

        Bitmap bitmap = imageToBitmap(image);
        if (bitmap == null) return;

        // 先画模拟检测框，验证悬浮层能否盖住原厂摄像头界面。
        overlayView.post(() -> {
            overlayView.setCaptureInfo(captureWidth, captureHeight, screenWidth, screenHeight, fps);
            overlayView.setDemoBoxes(now);
        });

        // 每秒保存一张截图，验证录屏是否真的抓到了前置影像。
        if (now - lastSaveMs > 1000) {
            lastSaveMs = now;
            saveBitmap(bitmap);
        }
        bitmap.recycle();
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            Bitmap padded = Bitmap.createBitmap(captureWidth + rowPadding / pixelStride, captureHeight, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
            padded.recycle();
            return cropped;
        } catch (Throwable t) {
            updateOverlayStatus("Bitmap 转换失败: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void saveBitmap(Bitmap bitmap) {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File out = new File(dir, "screen_ai_" + ts + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos);
            fos.flush();
            fos.close();
            updateOverlayStatus("FPS " + String.format(Locale.US, "%.1f", getCurrentFps()) + " | 已保存截图");
        } catch (Throwable t) {
            updateOverlayStatus("保存截图失败: " + t.getClass().getSimpleName());
        }
    }

    private float getCurrentFps() {
        long elapsed = System.currentTimeMillis() - fpsStartMs;
        if (elapsed <= 0) return 0f;
        return frameCount * 1000f / elapsed;
    }

    private void startOverlay() {
        if (overlayView != null) return;
        overlayView = new OverlayView(this);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.START | Gravity.TOP;
        try {
            windowManager.addView(overlayView, lp);
        } catch (Throwable t) {
            // 没有悬浮窗权限时会失败。
        }
    }

    private void updateOverlayStatus(String s) {
        if (overlayView != null) {
            overlayView.post(() -> overlayView.setStatusText(s));
        }
    }

    private void stopProjection() {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {}
        virtualDisplay = null;
        try {
            if (imageReader != null) imageReader.close();
        } catch (Throwable ignored) {}
        imageReader = null;
        try {
            if (mediaProjection != null) mediaProjection.stop();
        } catch (Throwable ignored) {}
        mediaProjection = null;
    }

    private void stopOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        stopOverlay();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen AI Capture", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("A4L Screen AI Overlay")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        return builder.build();
    }
}
