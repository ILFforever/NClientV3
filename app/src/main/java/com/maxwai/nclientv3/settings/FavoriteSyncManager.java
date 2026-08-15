package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Two-way favorite sync.
 * <p>
 * Local and remote state alone cannot distinguish "added on the other side" from "removed on
 * this side" - both leave an id present in exactly one place. So each successful sync records
 * a baseline of the reconciled id set, and later syncs resolve the difference against it:
 *
 * <pre>
 * baseline local remote  -&gt; action
 *    -       y     y        none (added on both)
 *    -       y     -        upload
 *    -       -     y        download
 *    y       y     y        none
 *    y       y     -        delete locally  (removed on the web)
 *    y       -     y        delete remotely (removed in the app)
 *    y       -     -        none (removed on both)
 * </pre>
 * <p>
 * With no baseline - a first sync, or an upgrade from a version without one - there is no way
 * to tell the cases apart, so it falls back to a union and deletes nothing.
 */
public final class FavoriteSyncManager {
    private static final String PREFS = "Settings";
    private static final String KEY_BASELINE_READY = "favorite_sync_baseline_ready";
    /**
     * Gap between favorite writes. POST/DELETE favorite are documented at 15/min per user, so
     * 4s apart keeps a long run just inside the budget. Pacing up front beats discovering the
     * limit through 429s, because each rejection then costs a full minute of backoff.
     */
    private static final long WRITE_SPACING_MS = 4_100;
    /**
     * Favorite pages fetchable inside one minute before the 15/min read budget bites. Shorter
     * runs stay unpaced so a normal library still syncs at full speed.
     */
    private static final int READ_BURST_PAGES = 14;
    private static final int MAX_ATTEMPTS = 4;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private FavoriteSyncManager() {
    }

    /**
     * Starts a sync unless one is already running. The guard is process-wide on purpose: a sync
     * outlives the activity that started it, so an Activity-scoped flag would let a second run
     * start as soon as the user leaves and re-enters the screen. Two runs would race the same
     * rows, halve the shared rate budget and each write a baseline.
     *
     * @return false if a sync is already in flight, in which case the listener is never called
     */
    public static boolean sync(@NonNull Context context, @NonNull Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        Context applicationContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                runSync(applicationContext, listener);
            } finally {
                RUNNING.set(false);
            }
        }, "favorite-sync").start();
        return true;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void runSync(Context context, Listener listener) {
        try {
            Map<Integer, JSONObject> remoteItems = fetchRemoteFavorites(context, listener);
            Set<Integer> remoteIds = new HashSet<>(remoteItems.keySet());
            List<Integer> localIdsOldestFirst = Queries.FavoriteTable.getFavoriteIdsOldestFirst();
            Set<Integer> localIds = new HashSet<>(localIdsOldestFirst);

            boolean hasBaseline = hasBaseline(context);
            Set<Integer> baseline = hasBaseline
                ? Queries.FavoriteSyncBaselineTable.getBaselineIds() : new HashSet<>();

            List<Integer> toDownload = new ArrayList<>();
            List<Integer> toUpload = new ArrayList<>();
            List<Integer> toDeleteLocal = new ArrayList<>();
            List<Integer> toDeleteRemote = new ArrayList<>();

            for (int id : remoteIds) {
                if (localIds.contains(id)) continue;
                // Known to the baseline but gone locally means it was unfavorited in the app.
                if (hasBaseline && baseline.contains(id)) toDeleteRemote.add(id);
                else toDownload.add(id);
            }
            // Oldest first so the upload order matches the order they were favorited locally.
            for (int id : localIdsOldestFirst) {
                if (remoteIds.contains(id)) continue;
                if (hasBaseline && baseline.contains(id)) toDeleteLocal.add(id);
                else toUpload.add(id);
            }

            // Refresh stored metadata for everything the server still knows about, and give
            // downloads descending timestamps so SQLite reproduces the API's newest-first order.
            Set<Integer> deletingRemote = new HashSet<>(toDeleteRemote);
            int position = 0;
            long newestRemoteTime = System.currentTimeMillis();
            for (Map.Entry<Integer, JSONObject> entry : remoteItems.entrySet()) {
                if (deletingRemote.contains(entry.getKey())) continue;
                Queries.FavoriteTable.addFavoriteListItem(entry.getValue(), newestRemoteTime - position);
                position++;
            }

            int failed = 0;
            int completed = 0;
            int totalWrites = toUpload.size() + toDeleteRemote.size();

            long latestUploadedTime = System.currentTimeMillis();
            for (int id : toUpload) {
                WriteOutcome outcome = write(context, id, true);
                if (outcome == WriteOutcome.DISABLED)
                    throw new IOException("Favorites are disabled server-side");
                if (outcome == WriteOutcome.OK) {
                    latestUploadedTime = Math.max(System.currentTimeMillis(), latestUploadedTime + 1);
                    Queries.FavoriteTable.updateFavoriteTime(id, latestUploadedTime);
                } else if (outcome == WriteOutcome.GONE) {
                    // The gallery no longer exists upstream; drop it rather than retry forever.
                    Queries.FavoriteTable.removeFavorite(id);
                    localIds.remove(id);
                } else {
                    failed++;
                }
                listener.onProgress(true, ++completed, totalWrites);
            }

            for (int id : toDeleteRemote) {
                WriteOutcome outcome = write(context, id, false);
                if (outcome == WriteOutcome.DISABLED)
                    throw new IOException("Favorites are disabled server-side");
                // A gallery that is already gone upstream counts as successfully removed.
                if (outcome == WriteOutcome.FAILED) failed++;
                else remoteIds.remove(id);
                listener.onProgress(true, ++completed, totalWrites);
            }

            for (int id : toDeleteLocal) {
                Queries.FavoriteTable.removeFavorite(id);
                localIds.remove(id);
            }

            // The reconciled set is what both sides should now hold.
            Set<Integer> reconciled = new HashSet<>(localIds);
            reconciled.addAll(toDownload);
            reconciled.addAll(toUpload);
            reconciled.removeAll(toDeleteLocal);
            reconciled.removeAll(toDeleteRemote);

            // Only advance the baseline when nothing failed. A baseline recorded over a partial
            // run would describe a state neither side is in, and the next sync would read those
            // gaps as deletions.
            if (failed == 0) {
                Queries.FavoriteSyncBaselineTable.replaceBaseline(reconciled);
                markBaselineReady(context);
            }

            listener.onComplete(new Result(toDownload.size(), toUpload.size(),
                toDeleteLocal.size(), toDeleteRemote.size(), failed, !hasBaseline));
        } catch (IOException | JSONException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LogUtility.e("Favorite sync failed", e);
            listener.onFailure();
        }
    }

    /**
     * Fetches every page of remote favorites. The endpoint has no per_page parameter - the
     * server fixes the page size (25) - so a large library means many sequential requests
     * against a 15/min budget. Once the first response reveals the page count, later requests
     * are spaced only if the run is long enough to actually hit the limit.
     *
     * @return remote favorites keyed by id, in the order the API returned them
     */
    private static Map<Integer, JSONObject> fetchRemoteFavorites(Context context, Listener listener)
        throws IOException, JSONException, InterruptedException {
        Map<Integer, JSONObject> items = new LinkedHashMap<>();
        int page = 1;
        int pageCount = 1;
        do {
            if (page > 1 && pageCount > READ_BURST_PAGES) Thread.sleep(WRITE_SPACING_MS);
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
                    items.put(item.getInt("id"), item);
                }
            }
            listener.onProgress(false, page, pageCount);
            page++;
        } while (page <= pageCount);
        return items;
    }

    /**
     * Adds or removes a single remote favorite. A failure here is reported rather than thrown so
     * one bad gallery cannot abort the whole run.
     */
    private static WriteOutcome write(Context context, int galleryId, boolean favorite)
        throws InterruptedException {
        String url = String.format(Locale.US, Utility.getApiBaseUrl()
            + "galleries/%d/favorite", galleryId);
        Request request = new Request.Builder().url(url)
            .method(favorite ? "POST" : "DELETE", RequestBody.EMPTY).build();
        try (Response response = executeWithRateLimit(context, request)) {
            if (response.code() == 404) return WriteOutcome.GONE;
            // 503 means the allow_favorites feature flag is off server-side; retrying every
            // remaining gallery would just burn the rate limit for nothing.
            if (response.code() == 503) return WriteOutcome.DISABLED;
            if (!response.isSuccessful() || response.body() == null) return WriteOutcome.FAILED;
            boolean favorited = new JSONObject(response.body().string())
                .optBoolean("favorited", !favorite);
            return favorited == favorite ? WriteOutcome.OK : WriteOutcome.FAILED;
        } catch (IOException | JSONException e) {
            LogUtility.e("Favorite write failed for " + galleryId, e);
            return WriteOutcome.FAILED;
        } finally {
            // Space out writes even on failure, so a rejected burst does not turn into a
            // tighter burst of retries.
            Thread.sleep(WRITE_SPACING_MS);
        }
    }

    private static Response executeWithRateLimit(Context context, Request request)
        throws IOException, InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Response response = Global.getClient(context).newCall(request).execute();
            if (response.code() != 429 || attempt == MAX_ATTEMPTS - 1) return response;

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

    private static boolean hasBaseline(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_BASELINE_READY, false);
    }

    private static void markBaselineReady(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, 0).edit();
        editor.putBoolean(KEY_BASELINE_READY, true).apply();
    }

    /**
     * Forgets the baseline, so the next sync unions instead of deleting. Call this when the
     * account changes, since a baseline from another account describes the wrong library.
     */
    public static void resetBaseline(@NonNull Context context) {
        Queries.FavoriteSyncBaselineTable.clearBaseline();
        context.getSharedPreferences(PREFS, 0).edit()
            .putBoolean(KEY_BASELINE_READY, false).apply();
    }

    private enum WriteOutcome {OK, FAILED, GONE, DISABLED}

    public interface Listener {
        void onProgress(boolean uploading, int completed, int total);

        void onComplete(@NonNull Result result);

        void onFailure();
    }

    public static final class Result {
        public final int downloaded;
        public final int uploaded;
        public final int removedLocal;
        public final int removedRemote;
        public final int failed;
        /**
         * True when this run had no baseline and therefore merged additively.
         */
        public final boolean firstSync;

        Result(int downloaded, int uploaded, int removedLocal, int removedRemote,
               int failed, boolean firstSync) {
            this.downloaded = downloaded;
            this.uploaded = uploaded;
            this.removedLocal = removedLocal;
            this.removedRemote = removedRemote;
            this.failed = failed;
            this.firstSync = firstSync;
        }
    }
}
