package com.harborlens.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.CookieManager;

import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CpaAccount {
    private static final String KEY = "bsca-cpa-v1";
    private static final String LOGIN = Cpa.ROOT + "index.php/login/";

    static synchronized void save(Context context, String user, String password, boolean encoded) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        var json = new JSONObject().put("user", user).put("password", password).put("encoded", encoded);
        String value = pack(cipher.getIV()) + "." + pack(cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8)));
        if (!context.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit().putString("credentials", value).commit()) {
            throw new IOException("Could not save CPA credentials");
        }
    }

    static boolean configured(Context context) {
        return context.getSharedPreferences(KEY, Context.MODE_PRIVATE).contains("credentials");
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static JSONObject read(Context context) throws Exception {
        String value = context.getSharedPreferences(KEY, Context.MODE_PRIVATE).getString("credentials", "");
        if (value.isEmpty()) throw new Cpa.Auth("Set up a CPA account in Settings");
        String[] parts = value.split("\\.", 2);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }

    static synchronized String signIn(Context context, String rejectedCookie) throws IOException, Cpa.Auth {
        var cookies = CookieManager.getInstance();
        String current = cookies.getCookie(Cpa.ROOT);
        String present = session(current);
        if (present != null && !present.equals(session(rejectedCookie))) return current;
        JSONObject account;
        try {
            account = read(context);
        } catch (Cpa.Auth missing) {
            throw missing;
        } catch (Exception unavailable) {
            throw new Cpa.Auth("Re-enter your CPA account in Settings");
        }

        Connection session = Jsoup.newSession().timeout(12000).followRedirects(false).ignoreHttpErrors(true);
        Connection.Response landing = session.newRequest(Cpa.ROOT).execute();
        checkStatus(landing.statusCode());
        String password = account.optString("password");
        String field = account.optBoolean("encoded") ? password : md5(password);
        Connection.Response response = session.newRequest(LOGIN)
            .method(Connection.Method.POST).referrer(Cpa.ROOT)
            .header("Origin", "https://cpatos.gov.bd")
            .data("username", account.optString("user"), "password", field, "submit_login", "Login").execute();
        checkStatus(response.statusCode());
        String token = response.cookie("ci_session");
        var body = response.parse();
        if (token == null || token.isEmpty() || Cpa.loginRequired(body)) {
            throw new Cpa.Auth("CPA sign-in could not be confirmed. Check your account or use CPA web sign-in.");
        }
        String location = response.header("Location");
        if (location != null && location.toLowerCase(Locale.ROOT).matches(".*(?:/|^)login/?(?:[?#].*)?")) {
            throw new Cpa.Auth("CPA returned to sign-in. Check your account.");
        }
        cookies.setCookie(Cpa.ROOT, "ci_session=" + token + "; Path=/; Secure; HttpOnly; Max-Age=7200");
        cookies.flush();
        return "ci_session=" + token;
    }

    private static void checkStatus(int status) throws IOException, Cpa.Auth {
        if (status == 429 || status >= 500) throw new IOException("CPA temporarily unavailable");
        if (status >= 400) throw new Cpa.Auth("CPA rejected sign-in (HTTP " + status + ")");
    }

    private static String session(String cookie) {
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("ci_session=") && trimmed.length() > "ci_session=".length()) return trimmed;
        }
        return null;
    }

    private static String md5(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(32);
            for (byte value : digest) output.append(String.format(Locale.ROOT, "%02x", value & 255));
            return output.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static String pack(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static SecretKey key() throws Exception {
        var store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
