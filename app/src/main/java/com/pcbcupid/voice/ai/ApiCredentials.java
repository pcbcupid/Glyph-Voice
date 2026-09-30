package com.pcbcupid.voice.ai;

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

/** User-owned keys, encrypted at rest; no bundled secrets, logs, backups or autofill. */
public final class ApiCredentials {
    private static final String ALIAS = "glyph-api-keys-v1";
    private final SharedPreferences preferences;
    public ApiCredentials(Context context) { this(context, "ai_credentials"); }
    ApiCredentials(Context context, String name) { preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    public AiProvider provider() {
        try { return AiProvider.valueOf(preferences.getString("selected", "DEEPSEEK")); }
        catch (IllegalArgumentException e) { return AiProvider.DEEPSEEK; }
    }
    public boolean hasKey(AiProvider provider) { return preferences.contains(provider.name() + ".key"); }
    public String model(AiProvider provider) { return preferences.getString(provider.name() + ".model", provider.defaultModel); }
    private SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    public synchronized void save(AiProvider provider, String key, String model) throws Exception {
        key = key.trim(); model = model.trim();
        if (!model.matches("[A-Za-z0-9._:/-]{1,120}")) throw new IllegalArgumentException("Enter a valid model ID.");
        if (key.isEmpty() && !hasKey(provider)) throw new IllegalArgumentException("Enter your API key.");
        if (!key.isEmpty() && !key.matches("[!-~]{8,1024}")) throw new IllegalArgumentException("Check the API key (no spaces or newlines).");
        SharedPreferences.Editor editor = preferences.edit();
        if (!key.isEmpty()) {
            byte[] plaintext = key.getBytes(StandardCharsets.UTF_8);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
                cipher.updateAAD(provider.name().getBytes(StandardCharsets.UTF_8));
                editor.putString(provider.name() + ".key", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP));
                editor.putString(provider.name() + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            } finally { Arrays.fill(plaintext, (byte) 0); }
        }
        if (!editor.putString("selected", provider.name()).putString(provider.name() + ".model", model).commit())
            throw new java.io.IOException("Could not save API settings.");
    }
    public synchronized String key(AiProvider provider) throws Exception {
        if (!hasKey(provider)) throw new IllegalStateException("API key missing");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(preferences.getString(provider.name() + ".iv", ""), Base64.NO_WRAP);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(provider.name().getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(Base64.decode(preferences.getString(provider.name() + ".key", ""), Base64.NO_WRAP));
        try { return new String(plaintext, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }
    public synchronized void remove(AiProvider provider) throws Exception {
        if (!preferences.edit().remove(provider.name() + ".key").remove(provider.name() + ".iv").commit())
            throw new java.io.IOException("Could not remove API key.");
    }
}
