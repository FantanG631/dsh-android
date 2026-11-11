package com.dsh.phone;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class TarGz {

    public interface Symlinker { void link(String target, String linkPath) throws Exception; }
    public static Symlinker symlinker = (t, l) -> android.system.Os.symlink(t, l);

    public static final class Stats {
        public int files, dirs, links, skipped, errors;
        public String firstError;
    }

    public static Stats extract(InputStream raw, File dest) throws IOException {
        Stats st = new Stats();
        File root = dest.getCanonicalFile();
        root.mkdirs();
        java.io.BufferedInputStream bis = new java.io.BufferedInputStream(raw, 1 << 16);
        bis.mark(2);
        int b1 = bis.read();
        int b2 = bis.read();
        bis.reset();
        java.io.InputStream in = (b1 == 0x1f && b2 == 0x8b)
                ? new GZIPInputStream(bis, 1 << 16) : bis;
        byte[] hdr = new byte[512];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;

        while (true) {
            if (!readFully(in, hdr, 512)) break;
            if (isZero(hdr)) break;

            String name = cstr(hdr, 0, 100);
            String prefix = cstr(hdr, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = parseNum(hdr, 124, 12);
            int mode = (int) parseNum(hdr, 100, 8);
            char type = (char) (hdr[156] & 0xff);
            String link = cstr(hdr, 157, 100);

            if (type == 'L') {
                String s = new String(read(in, size), StandardCharsets.UTF_8);
                skipPadding(in, size);
                int nul = s.indexOf('\0');
                longName = nul >= 0 ? s.substring(0, nul) : s;
                continue;
            }
            if (type == 'K') {
                String s2 = new String(read(in, size), StandardCharsets.UTF_8);
                skipPadding(in, size);
                int nul = s2.indexOf('\0');
                longLink = nul >= 0 ? s2.substring(0, nul) : s2;
                continue;
            }
            if (type == 'x' || type == 'g') {
                String data = new String(read(in, size), StandardCharsets.UTF_8);
                skipPadding(in, size);
                for (String rec : data.split("\n")) {
                    int eq = rec.indexOf('=');
                    if (eq < 0) continue;
                    int sp = rec.indexOf(' ');
                    if (sp < 0 || sp > eq) continue;
                    String key = rec.substring(sp + 1, eq);
                    String val = rec.substring(eq + 1);
                    if (key.equals("path")) paxPath = val;
                    else if (key.equals("linkpath")) paxLink = val;
                }
                continue;
            }

            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            if (paxPath != null) { name = paxPath; paxPath = null; }
            if (paxLink != null) { link = paxLink; paxLink = null; }
            while (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty() || name.equals(".")) { skip(in, size); st.skipped++; continue; }

            if (type == '5') {
                File d = new File(root, name);
                if (!safe(root, d)) { skip(in, size); st.skipped++; continue; }
                d.mkdirs();
                try {
                    android.system.Os.chmod(d.getAbsolutePath(), (mode & 07777) | 0700);
                } catch (Throwable ignored) {
                }
                skip(in, size);
                st.dirs++;
            } else if (type == '1') {
                File l = new File(root, name);
                File target = new File(root, link);
                if (!safe(root, l)) { skip(in, size); st.skipped++; continue; }
                File parent = l.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    if (target.exists()) {
                        if (l.exists() || isSymlink(l)) l.delete();
                        try {
                            android.system.Os.link(target.getAbsolutePath(), l.getAbsolutePath());
                        } catch (Throwable linkFail) {
                            java.nio.file.Files.copy(target.toPath(), l.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        st.links++;
                    } else {
                        st.skipped++;
                    }
                } catch (Throwable t) {
                    st.errors++;
                    if (st.firstError == null) st.firstError = "hardlink " + name + ": " + t;
                }
                skip(in, size);
            } else if (type == '2') {
                File l = new File(root, name);
                if (!safe(root, l)) { skip(in, size); st.skipped++; continue; }
                File p = l.getParentFile();
                if (p != null) p.mkdirs();
                try {
                    if (l.exists() || isSymlink(l)) l.delete();
                    symlinker.link(link, l.getAbsolutePath());
                    st.links++;
                } catch (Throwable t) {
                    st.errors++;
                    if (st.firstError == null) st.firstError = "symlink " + name + ": " + t;
                }
                skip(in, size);
            } else if (type == '0' || type == 0 || type == '7') {
                File f = new File(root, name);
                if (!safe(root, f)) { skip(in, size); st.skipped++; continue; }
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    copy(in, fos, size);
                    try {
                        android.system.Os.chmod(f.getAbsolutePath(), mode & 07777);
                    } catch (Throwable ignored) {
                    }
                    st.files++;
                } catch (Throwable t) {
                    st.errors++;
                    if (st.firstError == null) st.firstError = "file " + name + ": " + t;
                    skip(in, size); // 尽力对齐（copy 可能已消费部分）
                }
            } else {
                skip(in, size);
                st.skipped++;
            }
        }
        return st;
    }

    private static boolean safe(File root, File f) {
        try {
            return f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSymlink(File f) {
        try { return java.nio.file.Files.isSymbolicLink(f.toPath()); } catch (Throwable t) { return false; }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static byte[] read(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, (int) (size - off));
            if (n < 0) break;
            off += n;
        }
        return buf;
    }

    private static void copy(InputStream in, FileOutputStream out, long size) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
        }
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        int pad = (int) ((512 - (size % 512)) % 512);
        if (pad > 0) in.readNBytes(pad);
    }

    private static void skip(InputStream in, long size) throws IOException {
        long left = size + ((512 - (size % 512)) % 512);
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) break;
            left -= n;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long parseNum(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        String s = cstr(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return 0; }
    }

    public static void mainUnused(String[] args) throws Exception {
        File dest = new File(args[1]);
        try (InputStream in = new FileInputStream(args[0])) {
            Stats st = extract(in, dest);
            System.out.println("files=" + st.files + " dirs=" + st.dirs + " links=" + st.links
                    + " skipped=" + st.skipped + " errors=" + st.errors);
            if (st.firstError != null) System.out.println("firstError: " + st.firstError);
        }
    }
}
