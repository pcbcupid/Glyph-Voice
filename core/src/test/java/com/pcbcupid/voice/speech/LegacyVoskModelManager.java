package com.pcbcupid.voice.speech;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Only the pinned official English model is accepted. No downloader or network dependency. */
public final class LegacyVoskModelManager implements ModelManager {
    public static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    public static final String SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    private static final long MAX_ZIP = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED = 128L * 1024 * 1024;
    private final File root, installed;
    public LegacyVoskModelManager(File root) {
        this.root = root;
        installed = new File(root, MODEL_NAME);
    }
    @Override public boolean isInstalled() {
        return new File(installed, ".complete").isFile() && valid(installed);
    }
    @Override public File modelDirectory() { return installed; }
    private static boolean valid(File dir) {
        for (String path : new String[]{"am/final.mdl", "conf/model.conf", "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst"})
            if (!new File(dir, path).isFile() || new File(dir, path).length() == 0) return false;
        return true;
    }
    @Override public synchronized void install(InputStream input) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create model storage");
        File archive = new File(root, "model-import.zip");
        File staging = new File(root, "model-staging");
        File backup = new File(root, "model-previous");
        // Recover an interrupted directory promotion before starting another import.
        if (!installed.exists() && backup.exists()) Files.move(backup.toPath(), installed.toPath());
        deleteTree(staging);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = input; OutputStream out = new FileOutputStream(archive)) {
                byte[] block = new byte[32768];
                long total = 0;
                int count;
                while ((count = source.read(block)) != -1) {
                    if ((total += count) > MAX_ZIP) throw new IOException("Model ZIP is too large");
                    out.write(block, 0, count);
                    digest.update(block, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!SHA256.equals(hex.toString())) throw new IOException("Wrong or damaged English model archive");
            if (!staging.mkdirs()) throw new IOException("Cannot create model staging directory");
            extract(archive, staging);
            if (!valid(staging)) throw new IOException("Model files are missing");
            Files.write(new File(staging, ".complete").toPath(), SHA256.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            deleteTree(backup);
            if (installed.exists()) Files.move(installed.toPath(), backup.toPath());
            try { Files.move(staging.toPath(), installed.toPath()); }
            catch (IOException e) {
                if (backup.exists()) Files.move(backup.toPath(), installed.toPath());
                throw e;
            }
            deleteTree(backup);
        } finally {
            Files.deleteIfExists(archive.toPath());
            deleteTree(staging);
        }
    }
    private static void extract(File archive, File staging) throws IOException {
        long total = 0;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] block = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 1000) throw new IOException("Too many model entries");
                String name = entry.getName();
                String prefix = MODEL_NAME + "/";
                if (!name.startsWith(prefix) || name.contains("\\") || !names.add(name))
                    throw new IOException("Invalid model archive path");
                name = name.substring(prefix.length());
                if (name.isEmpty()) continue;
                File target = new File(staging, name);
                if (!target.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator))
                    throw new IOException("Unsafe model archive path");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create model directory");
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create model directory");
                    try (OutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(block)) != -1) {
                            if ((total += count) > MAX_EXPANDED) throw new IOException("Expanded model too large");
                            out.write(block, 0, count);
                        }
                    }
                }
            }
        }
    }
    /** Only called on fixed, app-owned model paths, never on user-selected files. */
    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.delete(file.toPath());
    }
}
