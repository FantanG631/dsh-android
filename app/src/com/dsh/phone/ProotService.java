package com.dsh.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;

public class ProotService extends Service {

    static final String CHANNEL_ID = "dsh-runtime";
    static final int NOTIF_ID = 1;
    static final int PORT = 3080;
    static final String ROOTFS_URL = "https://github.com/FantanG631/dsh-android/releases/download/v1.0.0/rootfs-clean.tar.gz";
    static final String ROOTFS_SHA256 = "40443af5a594243cb11a5156919478b0a0582312f5b54f1f9efc6fcfc3e1f41c";

    private volatile boolean running = true, tokenWatcherStarted = false;
    private Process dsh;
    private File files, bin, rootfs, ptmp, l2s, shm;
    private long lastStart = 0;
    private PowerManager.WakeLock wakeLock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, notif("准备运行环境…"));
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:proot");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (dsh == null) new Thread(this::loop, "dsh-supervisor").start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        if (dsh != null) dsh.destroy();
        super.onDestroy();
    }

    private void loop() {
        try {
            files = getFilesDir();
            bin = new File(files, "bin");
            rootfs = new File(files, "rootfs");
            ptmp = new File(files, "ptmp");
            l2s = new File(files, "l2s");
            shm = new File(files, "shm");
            bin.mkdirs(); ptmp.mkdirs(); l2s.mkdirs(); shm.mkdirs();
            new File(rootfs, "sdcard").mkdirs();
            new File(rootfs, "storage/emulated/0").mkdirs();
            prepareAssets();
            if (!rootfsLooksReady()) {
                File local = new File("/sdcard/Download/DSH/rootfs.tar");
                File pkg = local.exists() ? local : fetchRootfs();
                if (pkg == null) { update("下载失败，稍后重试"); return; }
                update("正在解压 rootfs…");
                extract(pkg);
            }
            startTokenWatcher();
            while (running) {
                if (!healthy()) startDsh();
                Thread.sleep(8000);
            }
        } catch (Throwable t) {
            update("服务异常：" + t);
        }
    }

    private boolean rootfsLooksReady() {
        return new File(rootfs, "usr/bin/dsh").exists()
                && new File(rootfs, "usr/lib/node_modules/@deepseek-ai/dsh/package.json").exists()
                && new File(rootfs, "etc/passwd").exists();
    }

    private void prepareAssets() {
        for (String name : new String[]{"proot.bin", "loader", "libtalloc.so.2", "libandroid-shmem.so"}) {
            File out = new File(bin, name);
            if (!out.exists() || out.length() == 0) {
                try (InputStream in = getAssets().open(name); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16]; int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } catch (Throwable t) { log("copy asset " + name + " failed: " + t); }
            }
            out.setReadable(true, false);
            if (name.equals("proot.bin") || name.equals("loader")) out.setExecutable(true, false);
        }
    }

    private File fetchRootfs() {
        File pkg = new File(files, "rootfs.tar.gz");
        File part = new File(files, "rootfs.tar.gz.part");
        for (int attempt = 1; attempt <= 3 && running; attempt++) {
            try {
                long have = part.length();
                update("正在下载运行环境（第 " + attempt + " 次）…");
                HttpURLConnection c = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                c.connect();
                int code = c.getResponseCode();
                long total = c.getContentLengthLong();
                if (have > 0 && code == 206) total += have; else have = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[1 << 16];
                    long done = have, lastNotif = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastNotif > 3000) {
                            lastNotif = now;
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            update("下载中 " + (done >> 20) + "MB" + (total > 0 ? " / " + (total >> 20) + "MB（" + pct + "%）" : ""));
                        }
                    }
                }
                if (ROOTFS_SHA256.length() > 0 && !sha256(part).equalsIgnoreCase(ROOTFS_SHA256)) {
                    log("sha mismatch, retry"); part.delete(); continue;
                }
                if (pkg.exists()) pkg.delete();
                if (!part.renameTo(pkg)) { log("rename failed"); continue; }
                return pkg;
            } catch (Throwable t) {
                log("download attempt " + attempt + " failed: " + t);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return null; }
            }
        }
        return null;
    }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void extract(File pkg) throws Exception {
        rootfs.mkdirs();
        TarGz.symlinker = (target, link) -> android.system.Os.symlink(target, link);
        try (InputStream in = new FileInputStream(pkg)) {
            TarGz.Stats st = TarGz.extract(in, rootfs);
            log("extracted files=" + st.files + " dirs=" + st.dirs + " links=" + st.links + " err=" + st.errors);
        }
        pkg.delete();
    }

    private ProcessBuilder prootBuilder(String... guestCmd) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(new File(bin, "proot.bin").getAbsolutePath());
        cmd.add("--kill-on-exit"); cmd.add("--sysvipc"); cmd.add("--change-id=0:0");
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath());
        cmd.add("-b"); cmd.add("/dev"); cmd.add("-b"); cmd.add("/proc"); cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add(shm.getAbsolutePath() + ":/dev/shm");
        // 手机存储（targetSdk 28 + 读写权限 = 传统存储模型，可直接访问）
        cmd.add("-b"); cmd.add("/storage/emulated/0:/sdcard");
        cmd.add("-b"); cmd.add("/storage/emulated/0");
        cmd.add("-w"); cmd.add("/root");
        for (String c : guestCmd) cmd.add(c);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PROOT_LOADER", new File(bin, "loader").getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", ptmp.getAbsolutePath());
        pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", bin.getAbsolutePath());
        pb.redirectErrorStream(true);
        return pb;
    }

    private boolean healthy() {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500); return true; }
        catch (Throwable t) { return false; }
    }

    private synchronized void startDsh() {
        if (System.currentTimeMillis() - lastStart < 20000) return;
        lastStart = System.currentTimeMillis();
        try {
            String cmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                    + "cd /root && exec dsh --profile phone --port " + PORT + " --no-open";
            ProcessBuilder pb = prootBuilder("/bin/bash", "-lc", cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(files, "dsh.log")));
            update("正在启动 DSH…");
            dsh = pb.start();
            log("proot started");
            startTokenWatcher();
        } catch (Throwable t) { log("startDsh EX " + t); }
    }

    private void startTokenWatcher() {
        if (tokenWatcherStarted || files == null || rootfs == null) return;
        tokenWatcherStarted = true;
        Thread t = new Thread(() -> {
            final java.util.regex.Pattern P = java.util.regex.Pattern
                    .compile("http://127\\.0\\.0\\.1:" + PORT + "/\\?token=[A-Za-z0-9_-]+");
            File logFile = new File(files, "dsh.log");
            long pos = 0;
            while (running) {
                try {
                    if (logFile.exists()) {
                        long len = logFile.length();
                        if (len < pos) pos = 0;
                        if (len > pos) {
                            byte[] buf = new byte[(int) Math.min(len - pos, 1 << 20)];
                            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                                raf.seek(pos);
                                int n = raf.read(buf);
                                if (n > 0) {
                                    pos += n;
                                    java.util.regex.Matcher m = P.matcher(new String(buf, 0, n));
                                    String last = null;
                                    while (m.find()) last = m.group();
                                    if (last != null) publishToken(last);
                                }
                            }
                        }
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException ie) { return; }
                catch (Throwable ignored) { try { Thread.sleep(3000); } catch (InterruptedException ie) { return; } }
            }
        }, "dsh-token-watch");
        t.setDaemon(true); t.start();
    }

    private void publishToken(String url) {
        try {
            File tmp = new File(rootfs, "tmp");
            if (!tmp.exists()) tmp.mkdirs();
            try (FileOutputStream os = new FileOutputStream(new File(tmp, "dsh.log"), false)) {
                os.write(("dsh web: " + url + "\n").getBytes());
            }
            try (FileOutputStream os = new FileOutputStream(new File(files, "token.txt"), false)) {
                os.write(url.getBytes());
            }
            log("token captured: " + url.substring(0, Math.min(48, url.length())));
        } catch (Throwable t) { log("publishToken ERR " + t); }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DSH 运行环境", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification notif(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("DSH").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true).build();
    }

    private void update(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, notif(text));
        log(text);
    }

    private void log(String msg) {
        String line = System.currentTimeMillis() + " " + msg + "\n";
        try { android.util.Log.i("DSHTEST", msg); } catch (Throwable ignored) {}
        try (FileOutputStream os = new FileOutputStream(new File(files, "proot-service.log"), true)) {
            os.write(line.getBytes());
        } catch (Throwable ignored) {}
        try {
            File dir = new File("/sdcard/Download/DSH-test");
            if (dir.exists() || dir.mkdirs()) {
                try (FileOutputStream os = new FileOutputStream(new File(dir, "service.log"), true)) {
                    os.write(line.getBytes());
                }
            }
        } catch (Throwable ignored) {}
    }
}