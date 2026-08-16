package com.maxwai.nclientv3.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.BuildConfig;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class ApiAuthInterceptor implements Interceptor {
    private final boolean logRequests;
    @NonNull
    private final Context context;

    public ApiAuthInterceptor(@NonNull Context context, boolean logRequests) {
        this.context = context.getApplicationContext();
        this.logRequests = logRequests;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (logRequests)
            LogUtility.d("Requested url: " + request.url());
        // The host check is not redundant with the path check: this client is shared with
        // unrelated destinations, and a path prefix alone would hand the user's token to any of
        // them that happened to serve /api/v2/.
        if (request.header("Authorization") != null
            || !Utility.isSiteHost(request.url().host())
            || !request.url().encodedPath().startsWith("/api/v2/")) {
            return chain.proceed(request);
        }

        Request.Builder r = request.newBuilder()
            .header("User-Agent", "NClient/" + BuildConfig.VERSION_NAME + " (https://github.com/ILFforever/NClientV3)");

        String authorization = Login.getUserTokenAuthorizationHeader();
        boolean usingApiKey = false;
        boolean favoritesRequest = request.url().encodedPath().contains("/favorite");
        if (authorization == null && AuthStore.hasValidApiKey(context)) {
            authorization = AuthStore.getAuthorizationHeader(context);
            usingApiKey = authorization != null;
        }
        if (authorization == null) return chain.proceed(r.build());

        if (favoritesRequest)
            LogUtility.d("Favorites API auth: " + (usingApiKey ? "API key" : "user token"));

        Request authenticated = r.header("Authorization", authorization)
            .build();
        Response response = chain.proceed(authenticated);
        if (favoritesRequest)
            LogUtility.d("Favorites API response: " + response.code() + " "
                + response.request().url().encodedPath());
        boolean rejected = response.code() == 401 || response.code() == 403;
        if (usingApiKey) {
            if (rejected) {
                AuthStore.setApiKeyValidation(context, false);
            } else if (response.isSuccessful()) {
                AuthStore.setApiKeyValidation(context, true);
            }
        } else if (rejected) {
            // A user token the server no longer accepts. Clearing it here rather than at each
            // call site is what stops the app from looping on a dead session: every later
            // Login.canAccessAuthenticatedApi check then reports the truth, and callers take
            // their offline path instead of failing the same way forever.
            Login.onSessionExpired(context);
        }
        return response;
    }
}
