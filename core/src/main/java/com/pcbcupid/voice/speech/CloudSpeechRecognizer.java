package com.pcbcupid.voice.speech;

import com.pcbcupid.voice.audio.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;

/** OpenAI-compatible multipart transcription, in bounded 15-second WAV chunks kept only in RAM. */
public final class CloudSpeechRecognizer implements StreamingSpeechRecognizer {
    public static final int CHUNK_SECONDS = 15;
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final String model, key;

    public static HttpUrl validateEndpoint(String value) {
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null)
            throw new IllegalArgumentException("Use a full HTTPS transcription URL without credentials, query or fragment.");
        return url;
    }
    public static void validateModel(String model) {
        if (!model.matches("[A-Za-z0-9._:/-]{1,120}"))
            throw new IllegalArgumentException("Enter your provider's speech model ID.");
    }
    public CloudSpeechRecognizer(String url, String model, String key) {
        this(new OkHttpClient.Builder(), validateEndpoint(url), model, key);
    }
    // Package-private to permit local HTTP MockWebServer tests; production always validates HTTPS.
    CloudSpeechRecognizer(OkHttpClient.Builder builder, HttpUrl url, String model, String key) {
        validateModel(model);
        if (!key.isEmpty() && !key.matches("[!-~]{1,1024}"))
            throw new IllegalArgumentException("API key must not contain spaces or newlines.");
        this.endpoint = url; this.model = model; this.key = key;
        client = builder.followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS).build();
    }
    @Override public Stream openStream(int rate) {
        new AudioFormat(rate, 1, "pcm_s16le");
        return new CloudStream(rate);
    }
    @Override public TranscriptionResult recognize(byte[] pcm) throws Exception {
        try (Stream stream = openStream(16000)) { stream.accept(pcm); return stream.finish(); }
    }
    @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }

    private final class CloudStream implements Stream {
        private final int rate;
        private final byte[] pending;
        private int used;
        private final StringBuilder text = new StringBuilder();
        private volatile boolean cancelled;
        private boolean finished;
        private volatile Call call;
        private long processingNanos;
        CloudStream(int rate) { this.rate = rate; pending = new byte[rate * 2 * CHUNK_SECONDS]; }
        private void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (finished) throw new IllegalStateException("Stream finished");
        }
        @Override public String accept(byte[] pcm) throws Exception {
            check();
            if (pcm.length % 2 != 0) throw new IllegalArgumentException("Unaligned PCM16");
            int offset = 0;
            while (offset < pcm.length) {
                check();
                int count = Math.min(pcm.length - offset, pending.length - used);
                System.arraycopy(pcm, offset, pending, used, count);
                used += count; offset += count;
                if (used == pending.length) upload();
            }
            return text.toString();
        }
        private void upload() throws Exception {
            check();
            if (used == 0) return;
            byte[] wav = wav(pending, used, rate);
            long begin = System.nanoTime();
            try {
                RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", model).addFormDataPart("response_format", "json")
                        .addFormDataPart("file", "speech.wav", RequestBody.create(wav, MediaType.get("audio/wav"))).build();
                Request.Builder request = new Request.Builder().url(endpoint).post(body);
                if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
                call = client.newCall(request.build());
                if (cancelled) call.cancel();
                try (Response response = call.execute()) {
                    check();
                    if (!response.isSuccessful()) throw new IOException("Cloud transcription HTTP " + response.code());
                    if (response.body() == null) throw new IOException("Empty transcription response");
                    // Limit a provider response before parsing, never echo its body or credentials in errors.
                    okio.Buffer buffer = new okio.Buffer();
                    long total = 0, count;
                    while ((count = response.body().source().read(buffer, Math.min(8192, 65537 - total))) != -1) {
                        total += count;
                        if (total > 65536) throw new IOException("Transcription response too large");
                    }
                    byte[] bytes = buffer.readByteArray();
                    Object value = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).opt("text");
                    if (!(value instanceof String)) throw new IOException("Expected JSON with a text field");
                    String words = ((String) value).trim();
                    if (!words.isEmpty()) { if (text.length() > 0) text.append(' '); text.append(words); }
                }
            } finally {
                processingNanos += System.nanoTime() - begin;
                call = null;
                Arrays.fill(wav, (byte) 0); Arrays.fill(pending, (byte) 0); used = 0;
            }
        }
        @Override public TranscriptionResult finish() throws Exception {
            check(); upload(); finished = true;
            return new TranscriptionResult(text.toString(), processingNanos / 1_000_000);
        }
        @Override public void cancel() { cancelled = true; Call active = call; if (active != null) active.cancel(); }
        @Override public void close() { cancel(); Arrays.fill(pending, (byte) 0); }
    }
    static byte[] wav(byte[] pcm, int length, int rate) {
        ByteBuffer out = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        out.putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length).put(pcm, 0, length);
        return out.array();
    }
}
