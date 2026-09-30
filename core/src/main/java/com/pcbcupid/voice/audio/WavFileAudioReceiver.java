package com.pcbcupid.voice.audio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Development input, using the same receiver callbacks as the network adapter. */
public final class WavFileAudioReceiver implements AudioReceiver {
    public interface Source { InputStream open() throws IOException; }
    private final Source source;
    private final boolean realtime;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;
    private Future<?> task;
    public WavFileAudioReceiver(Source source) { this(source, false); }
    public WavFileAudioReceiver(Source source, boolean realtime) { this.source = source; this.realtime = realtime; }
    @Override public void connect(String ignored, Listener listener) {
        stopped = false;
        task = worker.submit(() -> {
            try (InputStream in = new BufferedInputStream(source.open())) {
                if (!"RIFF".equals(tag(in))) throw new IOException("Expected a RIFF WAV file");
                long remaining = uint32(in);
                if (remaining < 4) throw new IOException("Invalid RIFF size");
                if (!"WAVE".equals(tag(in))) throw new IOException("Expected WAVE format");
                remaining -= 4;
                AudioFormat format = null;
                while (!stopped && remaining >= 8) {
                    String chunk = tag(in);
                    long size = uint32(in);
                    remaining -= 8;
                    long padded = size + (size & 1);
                    if (padded > remaining) throw new IOException("Truncated WAV chunk");
                    if ("fmt ".equals(chunk)) {
                        if (size < 16 || size > 4096) throw new IOException("Invalid WAV format chunk");
                        int encoding = uint16(in), channels = uint16(in);
                        long sampleRate = uint32(in), byteRate = uint32(in);
                        int alignment = uint16(in), bits = uint16(in);
                        if (encoding != 1 || bits != 16 || alignment != channels * 2 || byteRate != sampleRate * alignment)
                            throw new IOException("Use uncompressed 16-bit PCM WAV");
                        format = new AudioFormat((int) sampleRate, channels, "pcm_s16le");
                        skip(in, size - 16);
                    } else if ("data".equals(chunk)) {
                        if (format == null) throw new IOException("Missing WAV format before data");
                        if (size % 2 != 0) throw new IOException("Unaligned WAV audio");
                        listener.onConnection(Connection.CONNECTED);
                        listener.onStart("file", format);
                        long nextPacket = System.nanoTime();
                        while (size > 0 && !stopped) {
                            byte[] bytes = read(in, (int) Math.min(size, realtime ? format.sampleRate / 50 * 2 : 4096));
                            listener.onAudio(bytes);
                            size -= bytes.length;
                            if (realtime) {
                                nextPacket += bytes.length * 1_000_000_000L / (format.sampleRate * 2L);
                                long remainingNanos = nextPacket - System.nanoTime();
                                if (remainingNanos > 0) TimeUnit.NANOSECONDS.sleep(remainingNanos);
                            }
                        }
                        if (!stopped) listener.onEnd("file");
                        return;
                    } else skip(in, size);
                    if ((size & 1) != 0) read(in, 1);
                    remaining -= padded;
                }
                if (!stopped) throw new IOException("No audio data in WAV");
            } catch (Exception e) {
                if (!stopped) listener.onError("Test audio: " + e.getMessage());
            } finally { listener.onConnection(Connection.DISCONNECTED); }
        });
    }
    private static byte[] read(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n < 0) throw new EOFException("Truncated WAV");
            offset += n;
        }
        return bytes;
    }
    private static String tag(InputStream in) throws IOException { return new String(read(in, 4), StandardCharsets.US_ASCII); }
    private static int uint16(InputStream in) throws IOException { byte[] b = read(in, 2); return (b[0] & 255) | ((b[1] & 255) << 8); }
    private static long uint32(InputStream in) throws IOException {
        byte[] b = read(in, 4);
        return (b[0] & 255L) | ((b[1] & 255L) << 8) | ((b[2] & 255L) << 16) | ((b[3] & 255L) << 24);
    }
    private static void skip(InputStream in, long count) throws IOException {
        while (count > 0) { int n = (int) Math.min(count, 4096); read(in, n); count -= n; }
    }
    @Override public void disconnect() { stopped = true; if (task != null) task.cancel(true); }
    @Override public void close() { disconnect(); worker.shutdownNow(); }
}
