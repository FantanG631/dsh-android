package com.dsh.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.widget.EditText;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final int PORT = 3080;
    static final String BASE = "http://127.0.0.1:" + PORT + "/";
    final Pattern TOKEN = Pattern.compile("http://127\\.0\\.0\\.1:" + PORT + "/\\?token=[A-Za-z0-9_-]+");

    WebView web;
    TextView overlay;
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean serverReady = false;
    static final int REQ_FILE = 1001;
    ValueCallback<Uri[]> fileCallback;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            android.content.Intent svc = new android.content.Intent(this, ProotService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {}

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        try { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true); } catch (Throwable ignored) {}

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                if (u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost")) return false;
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, req.getUrl())); } catch (Throwable ignored) {}
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) { if (serverReady) hide(); }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame() && resp != null && resp.getStatusCode() == 401) {
                    show("正在登录…");
                    ui.postDelayed(MainActivity.this::tryToken, 600);
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError e) {
                if (!req.isForMainFrame()) return;
                serverReady = false;
                waitForServer();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        web.loadUrl(r.getUrl().toString());
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(Intent.createChooser(params.createIntent(), "选择文件"), REQ_FILE);
                } catch (Throwable t) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override public boolean onJsAlert(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setCancelable(false).show();
                return true;
            }

            @Override public boolean onJsConfirm(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                        .setCancelable(false).show();
                return true;
            }

            @Override public boolean onJsPrompt(WebView v, String u, String m, String def, final JsPromptResult r) {
                final EditText e = new EditText(MainActivity.this);
                e.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this).setMessage(m).setView(e)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm(e.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                        .setCancelable(false).show();
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setBackgroundColor(0xFF101418);
        overlay.setTextSize(14);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(48, 48, 48, 48);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        requestStoragePermission();
        requestBatteryExemption();
        waitForServer();
    }

    /** 申请存储权限：用于把手机存储挂进容器。 */
    void requestStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
            }
        } catch (Throwable ignored) {}
    }

    /** 申请忽略电池优化，避免熄屏后被 Doze 挂起。 */
    void requestBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) {}
    }

    void waitForServer() {
        show("正在启动 DSH…\n首次启动可能需要几十秒，请稍候");
        final long start = System.currentTimeMillis();
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
                    ui.post(() -> { serverReady = true; show("正在加载界面…"); web.loadUrl(BASE); });
                    return;
                } catch (Throwable t) {
                    long waited = System.currentTimeMillis() - start;
                    if (waited > 180000 && waited < 183000) {
                        ui.post(() -> show("仍在等待 DSH 启动…（已超过 3 分钟）\n若长时间无响应，请检查运行环境"));
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "wait-server").start();
    }

    void tryToken() {
        String url = findToken();
        if (url == null) {
            ui.postDelayed(MainActivity.this::tryToken, 2000);
            return;
        }
        web.loadUrl(url);
    }

    String findToken() {
        String[] paths = {
                new File(getFilesDir(), "rootfs/tmp/dsh.log").getAbsolutePath(),
                new File(getFilesDir(), "dsh.log").getAbsolutePath(),
                "/sdcard/Download/DSH/dsh.log"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                Matcher m = TOKEN.matcher(sb);
                String last = null;
                while (m.find()) last = m.group();
                if (last != null) return last;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    void show(String text) { overlay.setText(text); overlay.setVisibility(ViewGroup.VISIBLE); }
    void hide() { overlay.setVisibility(ViewGroup.GONE); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}