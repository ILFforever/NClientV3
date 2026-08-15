package com.maxwai.nclientv3.api.users;

import android.net.Uri;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a public user profile. The endpoint is rate limited to 5/min anonymously and
 * 10/min with credentials, so callers should fetch once per screen rather than per redraw.
 */
public class UserProfileFetcher extends Thread {
    private static final String PROFILE_API_URL = Utility.getBaseUrl() + "api/v2/users/%d/%s";
    private final int userId;
    private final String slug;
    private final Response callback;

    public UserProfileFetcher(int userId, String slug, @NonNull Response callback) {
        this.userId = userId;
        this.slug = slug;
        this.callback = callback;
    }

    @Override
    public void run() {
        String url = String.format(Locale.US, PROFILE_API_URL, userId, Uri.encode(slug));
        LogUtility.d("Fetching profile:", url);
        try (okhttp3.Response response = Objects.requireNonNull(Global.getClient())
            .newCall(new Request.Builder().url(url).build()).execute()) {
            ResponseBody body = response.body();
            if (body == null) {
                callback.onFailure(null);
                return;
            }
            if (!response.isSuccessful()) {
                LogUtility.e("Profile request failed:", response.code(), body.string());
                callback.onFailure(null);
                return;
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(body.byteStream()))) {
                callback.onSuccess(new UserProfile(reader));
            }
        } catch (NullPointerException | IOException | IllegalStateException e) {
            LogUtility.e("Error getting user profile", e);
            callback.onFailure(e);
        }
    }

    /**
     * Delivered on the fetcher thread; hop to the UI thread before touching views.
     */
    public interface Response {
        void onSuccess(@NonNull UserProfile profile);

        void onFailure(@Nullable Exception e);
    }
}
