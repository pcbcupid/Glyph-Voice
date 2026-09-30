package com.pcbcupid.voice.speech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Strict, offline-only installer for the four pinned Parakeet inference files. */
public final class LocalModelManager implements ModelManager {
    public static final String MODEL_NAME = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-1120ms";
    public static final String SHA256 = "ab5d28779f17ec0ce60ec537adc33f7fd5730d0adc68db0ab58ad5596b84eb4e";
    public static final long MODEL_BYTES = 663048980L;
    private static final String[] NAMES = {
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"};
    private static final long[] SIZES = {654046391L, 7257777L, 1735860L, 8952L};
    private static final String[] HASHES = {
            "1c03f1192de41771384af22972ca10203613ba56197a024f275b86727cd35911",
            "34fea72425d2506600772ba191a6d3f99c0710abdb68d9a3dc89fa8cb2aa473a",
            "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a",
            "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"};
    private final File root, installed;

    public LocalModelManager(File root) {
        this.root = root;
        installed = new File(root, MODEL_NAME);
    }

    @Override public boolean isInstalled() {
        File marker = new File(installed, ".complete");
        if (marker.length() != SHA256.length() || !valid(installed)) return false;
        try { return SHA256.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII)); }
        catch (IOException e) { return false; }
    }

    @Override public File modelDirectory() { return installed; }

    private static boolean valid(File directory) {
        for (int i = 0; i < NAMES.length; i++) {
            File file = new File(directory, NAMES[i]);
            if (!file.isFile() || file.length() != SIZES[i]) return false;
        }
        return true;
    }

    @Override public void install(InputStream input) throws Exception { install(input, percent -> {}); }

    @Override public synchronized void install(InputStream input, IntConsumer progress) throws Exception {
        File staging = new File(root, MODEL_NAME + ".staging");
        File backup = new File(root, MODEL_NAME + ".previous");
        try (InputStream source = input) {
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create model storage");
            // Recover interruption between moving the old directory and promoting the new one.
            if (!installed.exists() && backup.exists()) Files.move(backup.toPath(), installed.toPath());
            deleteTree(staging);
            if (root.getUsableSpace() < MODEL_BYTES + 64L * 1024 * 1024)
                throw new IOException("Not enough free storage to prepare Parakeet");
            if (!staging.mkdir()) throw new IOException("Cannot create model staging directory");
            extract(source, staging, progress);
            if (!valid(staging)) throw new IOException("Model files are missing");
            Files.write(new File(staging, ".complete").toPath(), SHA256.getBytes(StandardCharsets.US_ASCII));
            deleteTree(backup);
            if (installed.exists()) Files.move(installed.toPath(), backup.toPath());
            try { Files.move(staging.toPath(), installed.toPath()); }
            catch (IOException e) {
                if (backup.exists()) Files.move(backup.toPath(), installed.toPath());
                throw e;
            }
            deleteTree(backup);
        } finally { deleteTree(staging); }
    }

    private static void extract(InputStream input, File staging, IntConsumer progress) throws Exception {
        Set<String> seen = new HashSet<>();
        long total = 0;
        int lastPercent = -1;
        // Stream directly from APK to staging: no second 670 MB archive copy on the phone.
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input, 65536))) {
            ZipEntry entry;
            byte[] block = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                int index = -1;
                for (int i = 0; i < NAMES.length; i++)
                    if ((MODEL_NAME + "/" + NAMES[i]).equals(name)) index = i;
                // Exact allow-list rejects traversal, directories, extra files and duplicates.
                if (index < 0 || entry.isDirectory() || !seen.add(name))
                    throw new IOException("Unexpected model archive entry");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long written = 0;
                try (OutputStream output = new FileOutputStream(new File(staging, NAMES[index]))) {
                    int count;
                    while ((count = zip.read(block)) != -1) {
                        if ((written += count) > SIZES[index]) throw new IOException("Oversized model file");
                        output.write(block, 0, count);
                        digest.update(block, 0, count);
                        total += count;
                        int percent = (int) (total * 100 / MODEL_BYTES);
                        if (percent != lastPercent) { progress.accept(percent); lastPercent = percent; }
                    }
                }
                if (written != SIZES[index] || !HASHES[index].equals(hex(digest.digest())))
                    throw new IOException("Wrong or damaged Parakeet model file");
            }
        }
        if (seen.size() != NAMES.length) throw new IOException("Incomplete model archive");
    }

    private static String hex(byte[] hash) {
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    /** Fixed app-owned staging/backup paths only. Never called on selected documents. */
    private static void deleteTree(File path) throws IOException {
        if (!path.exists()) return;
        File[] children = path.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.delete(path.toPath());
    }
}
