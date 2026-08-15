package com.maxwai.nclientv3.settings;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class FavoriteSyncManager {
    public interface Listener {
        void onProgress(boolean uploading, int completed, int total);

        void onComplete(@NonNull Result result);

        void onFailure();
    }

    public static final class Result {
        public final int downloaded;
        public final int uploaded;
        public final int failed;

        Result(int downloaded, int uploaded, int failed) {
            this.downloaded = downloaded;
            this.uploaded = uploaded;
            this.failed = failed;
        }
    }

    private FavoriteSyncManager() {
    }

    public static void sync(@NonNull Context context, @NonNull Listener listener) {
        Context applicationContext = context.getApplicationContext();
        new Thread(() -> runSync(applicationContext, listener), "favorite-sync").start();
    }

    private static void runSync(Context context, Listener listener) {
        try {
            Set<Integer> localIds = Queries.FavoriteTable.getAllFavoriteIds();
            Set<Integer> remoteIds = new HashSet<>();
            List<JSONObject> remoteItems = new ArrayList<>();

            int page = 1;
            int pageCount = 1;
            do {
                String url = Utility.getApiBaseUrl() + "favorites?page=" + page;
                Request request = new Request.Builder().url(url).build();
                try (Response response = executeWithRateLimit(context, request)) {
                    if (!response.isSuccessful() || response.body() == null)
                        throw new IOException("Favorites request failed with HTTP " + response.code());
                    JSONObject body = new JSONObject(response.body().string());
                    pageCount = Math.max(1, body.optInt("num_pages", 1));
                    JSONArray results = body.getJSONArray("result");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        remoteIds.add(item.getInt("id"));
                        remoteItems.add(item);
                    }
                }
                listener.onProgress(false, page, pageCount);
                page++;
            } while (page <= pageCount);

            int downloaded = 0;
            for (JSONObject item : remoteItems) {
                int id = item.getInt("id");
                Queries.FavoriteTable.addFavoriteListItem(item);
                if (!localIds.contains(id)) downloaded++;
            }

            Set<Integer> localOnly = new HashSet<>(localIds);
            localOnly.removeAll(remoteIds);
            int uploaded = 0;
            int failed = 0;
            int completed = 0;
            for (int id : localOnly) {
                String url = String.format(Locale.US, Utility.getApiBaseUrl()
                    + "galleries/%d/favorite", id);
                Request request = new Request.Builder().url(url)
                    .post(RequestBody.EMPTY).build();
                try (Response response = executeWithRateLimit(context, request)) {
                    if (response.isSuccessful() && response.body() != null
                        && new JSONObject(response.body().string()).optBoolean("favorited", false)) {
                        uploaded++;
                    } else {
                        failed++;
                    }
                }
                listener.onProgress(true, ++completed, localOnly.size());
            }

            listener.onComplete(new Result(downloaded, uploaded, failed));
        } catch (IOException | JSONException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LogUtility.e("Favorite sync failed", e);
            listener.onFailure();
        }
    }

    private static Response executeWithRateLimit(Context context, Request request)
        throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            Response response = Global.getClient(context).newCall(request).execute();
            if (response.code() != 429 || attempt == 3) return response;

            long waitMs = 60_000;
            String retryAfter = response.header("Retry-After");
            if (retryAfter != null) {
                try {
                    waitMs = Math.max(1_000, Math.min(60_000,
                        Long.parseLong(retryAfter.trim()) * 1000));
                } catch (NumberFormatException ignored) {
                }
            }
            response.close();
            Thread.sleep(waitMs);
        }
        throw new IOException("Favorite request retry exhausted");
    }
}
