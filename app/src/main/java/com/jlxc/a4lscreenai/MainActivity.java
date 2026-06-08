package com.jlxc.a4lscreenai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_SCREEN_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;

    private TextView logView;
    private MediaProjectionManager projectionManager;
    private boolean openFcameraAfterCapture = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        appendLog("环境：Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);
        appendLog("用途：录屏取帧 + 悬浮窗叠加，先验证能否抓到前置影像画面。AI 模型下一步接入。");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("A4L Screen AI Overlay Probe");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        Button b1 = new Button(this);
        b1.setText("1. 打开前置影像 Fcamera");
        b1.setOnClickListener(v -> openFcamera());
        root.addView(b1, new LinearLayout.LayoutParams(-1, -2));

        Button b2 = new Button(this);
        b2.setText("2. 开始录屏 + 悬浮窗，然后自动打开前置影像");
        b2.setOnClickListener(v -> startCapture(true));
        root.addView(b2, new LinearLayout.LayoutParams(-1, -2));

        Button b3 = new Button(this);
        b3.setText("3. 只开始录屏 + 悬浮窗");
        b3.setOnClickListener(v -> startCapture(false));
        root.addView(b3, new LinearLayout.LayoutParams(-1, -2));

        Button b4 = new Button(this);
        b4.setText("4. 停止录屏 + 悬浮窗");
        b4.setOnClickListener(v -> stopCaptureService());
        root.addView(b4, new LinearLayout.LayoutParams(-1, -2));

        Button b5 = new Button(this);
        b5.setText("5. 打开保存截图目录说明");
        b5.setOnClickListener(v -> showPathDialog());
        root.addView(b5, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(15);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void openFcamera() {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.ts.MainUI", "com.ts.main.fcamera.FcameraMainActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            appendLog("已尝试打开：com.ts.MainUI/com.ts.main.fcamera.FcameraMainActivity");
        } catch (Throwable t) {
            appendLog("打开 Fcamera 失败：" + t.getMessage());
            Toast.makeText(this, "打开 Fcamera 失败", Toast.LENGTH_LONG).show();
        }
    }

    private void startCapture(boolean autoOpenFcamera) {
        openFcameraAfterCapture = autoOpenFcamera;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            appendLog("缺少悬浮窗权限，正在打开授权页面。部分车机会默认允许，可以返回后再点开始。");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }

        appendLog("请求录屏权限。你的车机如果默认放行，可能不会弹授权框。");
        try {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQ_SCREEN_CAPTURE);
        } catch (Throwable t) {
            appendLog("请求录屏权限失败：" + t.getMessage());
        }
    }

    private void stopCaptureService() {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        stopService(intent);
        appendLog("已发送停止服务命令。");
    }

    private void showPathDialog() {
        String msg = "截图会保存到 App 专属目录：\n" +
                "Android/data/" + getPackageName() + "/files/Pictures/\n\n" +
                "也可以用 ADB 拉取：\n" +
                "adb pull /sdcard/Android/data/" + getPackageName() + "/files/Pictures/";
        new AlertDialog.Builder(this).setTitle("截图保存位置").setMessage(msg).setPositiveButton("知道了", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            appendLog("已从悬浮窗权限页返回，请重新点开始。");
            return;
        }
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                appendLog("录屏授权失败或被取消：resultCode=" + resultCode);
                return;
            }
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            appendLog("录屏服务已启动，悬浮窗会显示 FPS 和模拟检测框。每秒保存一张截图。 ");
            if (openFcameraAfterCapture) {
                handler.postDelayed(this::openFcamera, 800);
            }
        }
    }

    private void appendLog(String s) {
        if (logView == null) return;
        logView.append("\n" + s);
    }
}
