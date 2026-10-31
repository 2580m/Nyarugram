package org.telegram.botgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Bearer token is encrypted with a non-exportable Android Keystore key. */
public final class BotgramSession {
    private static final String KEY = "botgram.session.v1";
    private final SharedPreferences prefs;
    public String server;
    public String userId;
    public String displayName;
    public String token;
    private String savedCiphertext;

    public BotgramSession(Context context, int account) {
        prefs = context.getSharedPreferences("botgram.session." + account, Context.MODE_PRIVATE);
        server = prefs.getString("server", "http://10.0.2.2:5080");
        try {
            String encrypted = prefs.getString("token", null);
            if (encrypted == null) return;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
            token = new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
            userId = prefs.getString("userId", null);
            displayName = prefs.getString("displayName", "");
            if (userId == null) clear();
        } catch (Exception error) { clear(); }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY, null)).getSecretKey();
    }

    public void save(String server, JSONObject response) throws Exception {
        save(server, response, () -> true);
    }

    public boolean save(String server, JSONObject response, BooleanSupplier active) throws Exception {
        JSONObject user = response.getJSONObject("user");
        String newToken = response.getString("token");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(newToken.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        synchronized (prefs) {
            if (!active.getAsBoolean()) return false;
            if (!prefs.edit().putString("server", server).putString("token", encrypted)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("userId", user.getString("id")).putString("displayName", user.getString("displayName")).commit()) {
                throw new java.io.IOException("Unable to save login session");
            }
            this.server = server;
            this.token = newToken;
            this.userId = user.getString("id");
            this.displayName = user.getString("displayName");
            savedCiphertext = encrypted;
            if (!active.getAsBoolean()) {
                clearIfCurrent();
                return false;
            }
            return true;
        }
    }

    // A late/cancelled login must never erase a newer session for the same slot.
    public void clearIfCurrent() {
        synchronized (prefs) {
            if (savedCiphertext != null && savedCiphertext.equals(prefs.getString("token", null))) clear();
        }
    }

    public void clear() {
        token = null; userId = null; displayName = null;
        prefs.edit().remove("token").remove("iv").remove("userId").remove("displayName").apply();
    }
}
