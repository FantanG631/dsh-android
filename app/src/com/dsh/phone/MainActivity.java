package com.dsh.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebView 壳 + 首启进度界面。状态由 ProotService 写入 files/state.json。 */
public class MainActivity extends Activity {

    static final int PORT = 3080;
    static final String BASE = "http://127.0.0.1:" + PORT + "/";
    static final int REQ_WEB_FILE = 1001;
    static final int REQ_IMPORT = 1003;
    final Pattern TOKEN = Pattern.compile("http://127\\.0\\.0\\.1:" + PORT + "/\\?token=[A-Za-z0-9_-]+");

    WebView web;
    FrameLayout overlay;
    TextView title, checklist, detail;
    ProgressBar bar;
    Button retry, importBtn, updateBtn;
    final Handler ui = new Handler(Looper.getMainLooper());
    ValueCallback<Uri[]> fileCallback;
    volatile boolean serverReady = false;
    String lastPhase = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startServiceCompat();
        buildUi();
        requestPermissionsCompat();
        ui.post(poll);
        checkAppUpdate();
    }

    void startServiceCompat() {
        try {
            Intent svc = new Intent(this, ProotService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {}
    }

    void buildUi() {
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
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Throwable ignored) {}
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) { if (serverReady) overlay.setVisibility(ViewGroup.GONE); }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame() && resp != null && resp.getStatusCode() == 401) {
                    ui.postDelayed(MainActivity.this::tryToken, 600);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean d, boolean g, android.os.Message r) {
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        web.loadUrl(req.getUrl().toString()); return true;
                    }
                });
                ((WebView.WebViewTransport) r.obj).setWebView(popup); r.sendToTarget(); return true;
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(Intent.createChooser(p.createIntent(), "选择文件"), REQ_WEB_FILE); }
                catch (Throwable t) { fileCallback = null; return false; }
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m)
                        .setPositiveButton(android.R.string.ok, (x, y) -> r.confirm()).setCancelable(false).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String u, String m, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(m)
                        .setPositiveButton(android.R.string.ok, (x, y) -> r.confirm())
                        .setNegativeButton(android.R.string.cancel, (x, y) -> r.cancel()).setCancelable(false).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView v, String u, String m, String def, final JsPromptResult r) {
                final EditText e = new EditText(MainActivity.this);
                e.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this).setMessage(m).setView(e)
                        .setPositiveButton(android.R.string.ok, (x, y) -> r.confirm(e.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (x, y) -> r.cancel()).setCancelable(false).show();
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF0E1116);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(56, 56, 56, 56);

        title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setText("DSH");
        col.addView(title);

        checklist = new TextView(this); checklist.setTextColor(0xFF9AA4B2); checklist.setTextSize(14);
        checklist.setPadding(0, 24, 0, 8);
        col.addView(checklist);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(0);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12));

        detail = new TextView(this); detail.setTextColor(0xFFC7D0DB); detail.setTextSize(13);
        detail.setPadding(0, 12, 0, 24);
        col.addView(detail);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        retry = new Button(this); retry.setText("重试"); retry.setVisibility(ViewGroup.GONE);
        retry.setOnClickListener(v -> retryNow());
        importBtn = new Button(this); importBtn.setText("从本地导入"); importBtn.setVisibility(ViewGroup.GONE);
        importBtn.setOnClickListener(v -> pickLocal());
        updateBtn = new Button(this); updateBtn.setText("更新运行环境");
        updateBtn.setOnClickListener(v -> confirmUpdate());
        row.addView(retry); row.addView(importBtn); row.addView(updateBtn);
        col.addView(row);

        overlay.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** 每 500ms 读一次状态文件。 */
    final Runnable poll = new Runnable() {
        @Override public void run() {
            String st = readState();
            render(st);
            if (!"ready".equals(lastPhase)) ui.postDelayed(this, 500);
            else ui.postDelayed(this, 2000);
        }
    };

    String readState() {
        try {
            File f = new File(getFilesDir(), "state.json");
            if (!f.exists()) return "{\"phase\":\"check\",\"progress\":0,\"detail\":\"准备中\"}";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            return sb.toString();
        } catch (Throwable t) { return "{\"phase\":\"check\",\"progress\":0,\"detail\":\"读取状态失败\"}"; }
    }

    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"([^\"]*)\"|(-?\\d+)|null)").matcher(json);
        if (!m.find()) return "";
        if (m.group(2) != null) return m.group(2);
        if (m.group(3) != null) return m.group(3);
        return "";
    }

    void render(String json) {
        String phase = field(json, "phase");
        String detailText = field(json, "detail");
        String error = field(json, "error");
        int progress = 0;
        try { progress = Integer.parseInt(field(json, "progress")); } catch (Throwable ignored) {}
        lastPhase = phase;

        String check = ("check".equals(phase) || isLater(phase)) ? "✓" : "·";
        String dl = (isLater(phase) || "download".equals(phase)) ? ("download".equals(phase) ? "→" : "✓") : "·";
        String ex = ("extract".equals(phase) || isLater(phase)) ? ("extract".equals(phase) ? "→" : "✓") : "·";
        String rd = "ready".equals(phase) ? "✓" : "·";
        checklist.setText(check + " 检查环境\n" + dl + " 下载 / 校验运行环境\n" + ex + " 解压（约 2.5 GB）\n" + rd + " 启动 DSH");

        if ("error".equals(phase)) {
            title.setText("出错了");
            detail.setText(detailText + (error.isEmpty() ? "" : "\n\n" + error));
            bar.setVisibility(ViewGroup.GONE);
            retry.setVisibility(ViewGroup.VISIBLE);
            importBtn.setVisibility(ViewGroup.VISIBLE);
            updateBtn.setVisibility(ViewGroup.VISIBLE);
            overlay.setVisibility(ViewGroup.VISIBLE);
            return;
        }

        retry.setVisibility(ViewGroup.GONE);
        importBtn.setVisibility(ViewGroup.GONE);
        updateBtn.setVisibility("ready".equals(phase) ? ViewGroup.VISIBLE : ViewGroup.GONE);
        bar.setVisibility(ViewGroup.VISIBLE);
        title.setText("正在准备运行环境");
        detail.setText(detailText);
        if (progress >= 0) { bar.setIndeterminate(false); bar.setProgress(progress); }
        else bar.setIndeterminate(true);

        if ("ready".equals(phase)) {
            overlay.setVisibility(ViewGroup.GONE);
            if (!serverReady) waitForServer();
        }
    }

    static boolean isLater(String phase) {
        return "verify".equals(phase) || "extract".equals(phase) || "start".equals(phase) || "ready".equals(phase);
    }

    void retryNow() {
        overlay.setVisibility(ViewGroup.VISIBLE);
        title.setText("正在重试…");
        try {
            Intent i = new Intent(this, ProotService.class);
            i.setAction(ProotService.ACTION_RETRY);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {}
        ui.postDelayed(poll, 500);
    }

    void confirmUpdate() {
        new AlertDialog.Builder(this)
                .setTitle("更新运行环境")
                .setMessage("将重新下载并替换运行环境（约 695 MiB，解压约 2.5 GB）。\n会话与登录数据保存在运行环境内，更新后会丢失。\n确定继续？")
                .setPositiveButton("更新", (d, w) -> {
                    overlay.setVisibility(ViewGroup.VISIBLE);
                    title.setText("正在更新运行环境…");
                    try {
                        Intent i = new Intent(this, ProotService.class);
                        i.setAction(ProotService.ACTION_UPDATE);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i);
                        else startService(i);
                    } catch (Throwable ignored) {}
                    ui.postDelayed(poll, 500);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 后台检查 GitHub 最新版本；有新版则提示打开下载页。 */
    void checkAppUpdate() {
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/FantanG631/dsh-android/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                int ti = sb.indexOf("tag_name");
                if (ti < 0) return;
                int tj = ti + 8;
                while (tj < sb.length() && !Character.isDigit(sb.charAt(tj))) tj++;
                int tk = tj;
                while (tk < sb.length() && (Character.isDigit(sb.charAt(tk)) || sb.charAt(tk) == '.')) tk++;
                if (tk <= tj) return;
                String latest = sb.substring(tj, tk);
                String current;
                try {
                    current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Throwable t) { return; }
                if (isNewer(latest, current)) {
                    ui.post(() -> new AlertDialog.Builder(this)
                            .setTitle("发现新版本 v" + latest)
                            .setMessage("当前 " + current + "，是否前往下载？")
                            .setPositiveButton("前往", (d, w) -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/FantanG631/dsh-android/releases")));
                                } catch (Throwable ignored) {}
                            })
                            .setNegativeButton("以后", null).show());
                }
            } catch (Throwable ignored) {}
        }, "update-check").start();
    }

    static boolean isNewer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int n = i < x.length ? parseIntSafe(x[i]) : 0;
            int o = i < y.length ? parseIntSafe(y[i]) : 0;
            if (n != o) return n > o;
        }
        return false;
    }

    static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); } catch (Throwable t) { return 0; }
    }

    void pickLocal() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable ignored) {}
    }

    void waitForServer() {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
                    ui.post(() -> { serverReady = true; web.loadUrl(BASE); });
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "wait-server").start();
    }

    void tryToken() {
        String url = findToken();
        if (url == null) { ui.postDelayed(this::tryToken, 2000); return; }
        web.loadUrl(url);
    }

    String findToken() {
        String[] paths = {
                new File(getFilesDir(), "rootfs/tmp/dsh.log").getAbsolutePath(),
                new File(getFilesDir(), "dsh.log").getAbsolutePath()};
        for (String p : paths) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
                }
                Matcher m = TOKEN.matcher(sb);
                String last = null;
                while (m.find()) last = m.group();
                if (last != null) return last;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    void requestPermissionsCompat() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                java.util.List<String> need = new java.util.ArrayList<>();
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1002);
            }
        } catch (Throwable ignored) {}
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Throwable ignored) {}
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WEB_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        } else if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri src = data.getData();
            detail.setText("正在导入运行环境包…");
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(src);
                     FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "rootfs.tar.gz"))) {
                    byte[] buf = new byte[1 << 16]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Throwable ignored) {}
                ui.post(() -> { retryNow(); });
            }).start();
        }
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
