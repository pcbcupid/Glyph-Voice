package com.pcbcupid.voice.speech;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Separate transcription settings; summary credentials never become speech credentials. */
public final class SpeechSettings {
    private final SharedPreferences prefs;
    private static final String ALIAS = "glyph-speech-key-v1";
    public SpeechSettings(Context context) { prefs = context.getSharedPreferences("speech_settings", 0); }
    public boolean cloud() { return prefs.getBoolean("cloud", false); }
    public String endpoint() { return prefs.getString("endpoint", ""); }
    public String model() { return prefs.getString("model", ""); }
    public boolean hasKey() { return prefs.contains("key"); }
    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    public synchronized String key() throws Exception {
        if (!hasKey()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128,
                Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        cipher.updateAAD(endpoint().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = cipher.doFinal(Base64.decode(prefs.getString("key", ""), Base64.NO_WRAP));
        try { return new String(bytes, StandardCharsets.UTF_8); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    public synchronized void save(boolean cloud, String endpoint, String model, String key, boolean clearKey) throws Exception {
        SharedPreferences.Editor edit = prefs.edit().putBoolean("cloud", cloud);
        if (cloud) {
            endpoint = CloudSpeechRecognizer.validateEndpoint(endpoint).toString();
            model = model.trim(); key = key.trim();
            CloudSpeechRecognizer.validateModel(model);
            if (!key.isEmpty() && !key.matches("[!-~]{1,1024}"))
                throw new IllegalArgumentException("Check your API key (no spaces or newlines).");
            // Never silently send a saved credential to a changed URL.
            if (!endpoint.equals(endpoint()) || clearKey) edit.remove("key").remove("iv");
            if (!key.isEmpty() && !clearKey) {
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
                    cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
                    edit.putString("key", Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP));
                    edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
            edit.putString("endpoint", endpoint).putString("model", model);
        } else if (clearKey) edit.remove("key").remove("iv");
        if (!edit.commit()) throw new java.io.IOException("Could not save speech settings.");
    }
}
