package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.MainActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.CustomCookieJar;
import com.maxwai.nclientv3.loginapi.LoadTags;
import com.maxwai.nclientv3.loginapi.User;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

import org.json.JSONObject;

public class Login {
    public static final String LOGIN_COOKIE = "access_token";
    public static HttpUrl BASE_HTTP_URL;
    private static User user;
    private static boolean accountTag;
    private static Context applicationContext;

    public static void initLogin(@NonNull Context context) {
        applicationContext = context.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("Settings", 0);
        accountTag = preferences.getBoolean(context.getString(R.string.preference_key_use_account_tag), false);
        BASE_HTTP_URL = HttpUrl.get(Utility.getBaseUrl());
    }

    public static boolean useAccountTag() {
        return accountTag;
    }

    private static void removeCookie(String cookieName) {
        CustomCookieJar cookieJar = (CustomCookieJar) Global.client.cookieJar();
        cookieJar.removeCookie(cookieName);
    }

    public static void logout() {
        CustomCookieJar cookieJar = (CustomCookieJar) Global.client.cookieJar();
        removeCookie(LOGIN_COOKIE);
        cookieJar.clearSession();
        updateUser(null);
        clearOnlineTags();
        clearWebViewCookies();
        if (applicationContext != null) AuthStore.clearUserToken(applicationContext);
    }

    public static void clearWebViewCookies() {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Throwable ignore) {
        }
    }

    public static void clearOnlineTags() {
        Queries.TagTable.removeAllBlacklisted();
    }

    public static void clearCookies(@NonNull Context context) {
        CustomCookieJar cookieJar = (CustomCookieJar) Global.getClient(context).cookieJar();
        cookieJar.clear();
        cookieJar.clearSession();
    }

    public static void addOnlineTag(Tag tag) {
        Queries.TagTable.insert(tag);
        Queries.TagTable.updateBlacklistedTag(tag, true);
    }

    public static void removeOnlineTag(Tag tag) {
        Queries.TagTable.updateBlacklistedTag(tag, false);
    }

    public static boolean hasCookie(String name) {
        return getCookieValue(name) != null;
    }

    @Nullable
    public static String getCookieValue(String name) {
        if (Global.client == null || BASE_HTTP_URL == null) return null;
        List<Cookie> cookies = Global.client.cookieJar().loadForRequest(BASE_HTTP_URL);
        for (Cookie c : cookies) {
            if (c.name().equals(name) && !c.value().trim().isEmpty()) return c.value().trim();
        }
        return null;
    }

    @Nullable
    public static String getUserTokenAuthorizationHeader() {
        String accessToken = getCookieValue(LOGIN_COOKIE);
        if (accessToken == null && applicationContext != null)
            accessToken = AuthStore.getUserToken(applicationContext);
        return accessToken == null ? null : "User " + accessToken;
    }

    public static void importWebViewCookies(@NonNull Context context) {
        try {
            importCookieHeader(context, CookieManager.getInstance().getCookie(Utility.getBaseUrl()));
        } catch (Throwable ignore) {
        }
    }

    public static void importCookieHeader(@NonNull Context context, @Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;
        java.util.List<Cookie> cookieList = new java.util.ArrayList<>();
        for (String part : cookieHeader.split(";")) {
            Cookie cookie = Cookie.parse(BASE_HTTP_URL, part.trim() + "; Path=/");
            if (cookie != null && LOGIN_COOKIE.equals(cookie.name())) {
                cookie = persistentAccessToken(cookie);
                AuthStore.saveUserToken(context, cookie.value(), cookie.expiresAt());
            }
            if (cookie != null) cookieList.add(cookie);
        }
        if (!cookieList.isEmpty())
            Global.client.cookieJar().saveFromResponse(BASE_HTTP_URL, cookieList);
    }

    @NonNull
    private static Cookie persistentAccessToken(@NonNull Cookie cookie) {
        long expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30);
        try {
            String[] tokenParts = cookie.value().split("\\.");
            if (tokenParts.length == 3) {
                byte[] payload = android.util.Base64.decode(tokenParts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                long tokenExpiry = new JSONObject(new String(payload, StandardCharsets.UTF_8))
                    .optLong("exp", 0) * 1000;
                if (tokenExpiry > System.currentTimeMillis()) expiresAt = tokenExpiry;
            }
        } catch (Exception ignored) {
        }

        Cookie.Builder builder = new Cookie.Builder()
            .name(cookie.name())
            .value(cookie.value())
            .hostOnlyDomain(BASE_HTTP_URL.host())
            .path("/")
            .expiresAt(expiresAt);
        if (BASE_HTTP_URL.isHttps()) builder.secure();
        return builder.build();
    }

    public static void restorePersistedAccessToken(@NonNull Context context) {
        if (hasCookie(LOGIN_COOKIE)) return;
        String token = AuthStore.getUserToken(context);
        long expiresAt = AuthStore.getUserTokenExpiry(context);
        if (token == null || expiresAt <= System.currentTimeMillis()) return;

        Cookie.Builder builder = new Cookie.Builder()
            .name(LOGIN_COOKIE)
            .value(token)
            .hostOnlyDomain(BASE_HTTP_URL.host())
            .path("/")
            .expiresAt(expiresAt);
        if (BASE_HTTP_URL.isHttps()) builder.secure();
        Global.client.cookieJar().saveFromResponse(BASE_HTTP_URL,
            java.util.Collections.singletonList(builder.build()));
    }

    public static boolean canAccessAuthenticatedApi(@NonNull Context context) {
        return getCookieValue(LOGIN_COOKIE) != null || AuthStore.getUserToken(context) != null
            || AuthStore.hasValidApiKey(context);
    }

    public static boolean isLogged(@Nullable Context context) {
        boolean loggedIn = hasCookie(LOGIN_COOKIE);
        LogUtility.d("Login cookie present: " + loggedIn);
        if (loggedIn) {
            if (context != null && user == null) User.createUser(context, user -> {
                if (user != null) {
                    new LoadTags(context).start();
                    if (context instanceof MainActivity) {
                        ((MainActivity) context).runOnUiThread(((MainActivity) context)::loadStringLogin);
                    }
                }
            });
            return true;
        }
        if (context != null) logout();
        return false;
    }

    public static boolean isLogged() {
        return isLogged(null);
    }

    public static User getUser() {
        return user;
    }

    public static void updateUser(User user) {
        Login.user = user;
    }

    public static boolean isOnlineTags(Tag tag) {
        return Queries.TagTable.isBlackListed(tag);
    }
}
