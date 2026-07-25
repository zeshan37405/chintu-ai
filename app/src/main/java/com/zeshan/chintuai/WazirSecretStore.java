package com.zeshan.chintuai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-supplied Gemini key encrypted with Android Keystore. */
public final class WazirSecretStore {
    private static final String PREFS = "wazir_secure_preferences";
    private static final String KEY_CIPHERTEXT = "gemini_key_ciphertext";
    private static final String KEY_IV = "gemini_key_iv";
    private static final String KEY_ALIAS = "wazir_gen_z_gemini_key_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private WazirSecretStore() {
    }

    public static boolean saveGeminiApiKey(Context context, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.length() < 20) return false;
        try {
            SecretKey secretKey = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CIPHERTEXT,
                            Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV,
                            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public static String getGeminiApiKey(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encryptedText = preferences.getString(KEY_CIPHERTEXT, "");
        String ivText = preferences.getString(KEY_IV, "");
        if (encryptedText == null || encryptedText.isEmpty()
                || ivText == null || ivText.isEmpty()) return "";
        try {
            SecretKey secretKey = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            clearGeminiApiKey(context);
            return "";
        }
    }

    public static boolean hasGeminiApiKey(Context context) {
        return !getGeminiApiKey(context).isEmpty();
    }

    public static void clearGeminiApiKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .apply();
    }

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
