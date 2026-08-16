package com.maxwai.nclientv3.settings;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;

import com.maxwai.nclientv3.FavoriteActivity;
import com.maxwai.nclientv3.R;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
    /**
     * Whoever currently wants callbacks. A sync outlives the screen that started it, so listeners
     * subscribe and unsubscribe independently of the run rather than being handed to it: an
     * Activity recreated mid-sync can still receive the completion that its predecessor started,
     * and a destroyed one stops being retained by the sync thread.
     */
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile Progress progress = new Progress(false, 0, 0);

    private FavoriteSyncManager() {
    }

    /**
     * Subscribes to the running sync, or to the next one. Safe to call when nothing is running.
     * Every caller must pair this with {@link #removeListener} when it goes away, or the sync
     * thread keeps its listener - and whatever that listener captures - alive for the whole run.
     */
    public static void addListener(@NonNull Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public static void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Starts a sync unless one is already running. The guard is process-wide on purpose: a sync
     * outlives the activity that started it, so an Activity-scoped flag would let a second run
     * start as soon as the user leaves and re-enters the screen. Two runs would race the same
     * rows, halve the shared rate budget and each write a baseline.
     *
     * @return false if a sync is already in flight; subscribed listeners still hear that one out
     */
    public static boolean sync(@NonNull Context context) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        progress = new Progress(false, 0, 0);
        Context app = context.getApplicationContext();
        new Thread(() -> {
            int notificationId = NotificationSettings.getNotificationId();
            NotificationCompat.Builder notification = buildNotification(app);
            // Show it before the first request, so a slow account fetch is not dead air.
            NotificationSettings.notify(app, notificationId, notification.build());
            try {
                // Mirror every callback into the notification, so progress stays visible after
                // the user leaves the Favorites screen.
                runSync(app, new Listener() {
                    @Override
                    public void onProgress(boolean writing, int completed, int total) {
                        progress = new Progress(writing, completed, total);
                        publishProgress(app, notification, notificationId, writing, completed, total);
                        for (Listener l : listeners) l.onProgress(writing, completed, total);
                    }

                    @Override
                    public void onComplete(@NonNull Result result) {
                        publishResult(app, notification, notificationId,
                            R.string.favorite_sync_done, describe(app, result));
                        for (Listener l : listeners) l.onComplete(result);
                    }

                    @Override
                    public void onFailure() {
                        publishResult(app, notification, notificationId,
                            R.string.favorite_sync_failed_title,
                            app.getString(R.string.favorite_sync_failed));
                        for (Listener l : listeners) l.onFailure();
                    }
                });
            } finally {
                RUNNING.set(false);
            }
        }, "favorite-sync").start();
        return true;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Latest progress of the running sync. Kept as a snapshot rather than pushed to listeners so
     * a screen opened midway through - after the one that started the sync is long gone - can
     * still say what is happening instead of showing nothing.
     */
    public static Progress getProgress() {
        return progress;
    }

    /**
     * Human-readable description of the current phase, for a UI that joined late.
     */
    public static String describeProgress(@NonNull Context context) {
        Progress current = progress;
        return phaseText(context, current.writing, current.completed, current.total);
    }

    /**
     * The one place phase wording is decided, so the notification, the dialog and the toolbar
     * subtitle cannot describe the same moment differently. Reading pages the account's favorites
     * list; writing pushes both additions and removals, hence "saving changes" rather than
     * "uploading".
     */
    public static String phaseText(@NonNull Context context, boolean writing, int completed, int total) {
        if (total > 0) {
            return context.getString(writing
                    ? R.string.favorite_sync_writing_count : R.string.favorite_sync_reading_page,
                completed, total);
        }
        return context.getString(writing
            ? R.string.favorite_sync_writing : R.string.favorite_sync_reading);
    }

    /**
     * Renders a result as a short sentence, listing only the counts that are non-zero so a quiet
     * sync reads as three words instead of a row of zeroes. Shared by the toast and the
     * notification so the two can never drift.
     */
    public static String describe(@NonNull Context context, @NonNull Result result) {
        List<String> parts = new ArrayList<>();
        if (result.downloaded > 0)
            parts.add(context.getString(R.string.favorite_sync_added, result.downloaded));
        if (result.uploaded > 0)
            parts.add(context.getString(R.string.favorite_sync_uploaded, result.uploaded));
        int removed = result.removedLocal + result.removedRemote;
        if (removed > 0) parts.add(context.getString(R.string.favorite_sync_removed, removed));
        if (result.failed > 0)
            parts.add(context.getString(R.string.favorite_sync_failed_count, result.failed));

        if (parts.isEmpty()) return context.getString(R.string.favorite_sync_no_changes);
        String summary = TextUtils.join(", ", parts);
        return context.getString(result.firstSync
            ? R.string.favorite_sync_first_run : R.string.favorite_sync_result, summary);
    }

    private static NotificationCompat.Builder buildNotification(Context context) {
        Intent intent = new Intent(context, FavoriteActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new NotificationCompat.Builder(context, Global.CHANNEL_ID4)
            .setSmallIcon(R.drawable.ic_favorite)
            .setContentTitle(context.getString(R.string.favorite_sync_notification_title))
            .setContentText(context.getString(R.string.favorite_sync_reading))
            .setContentIntent(PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true);
    }

    private static void publishProgress(Context context, NotificationCompat.Builder notification,
                                        int notificationId, boolean writing, int completed, int total) {
        notification.setContentText(phaseText(context, writing, completed, total))
            .setProgress(Math.max(total, 1), completed, total <= 0);
        NotificationSettings.notify(context, notificationId, notification.build());
    }

    /**
     * @param title must reflect the outcome; a failed sync titled "complete" contradicts its own body
     */
    private static void publishResult(Context context, NotificationCompat.Builder notification,
                                      int notificationId, @StringRes int title, String summary) {
        notification.setContentTitle(context.getString(title))
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true);
        NotificationSettings.notify(context, notificationId, notification.build());
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

            Plan plan = plan(remoteIds, localIdsOldestFirst, baseline, hasBaseline);

            // Refresh stored metadata for everything the server still knows about. Only entries
            // that are new to this device get a sort time: they are ordered by remote position so
            // SQLite reproduces the API's newest-first order. Galleries the user already had keep
            // the time they were originally favorited, otherwise every sync would rewrite the
            // whole list into whatever order the server happens to report.
            Set<Integer> deletingRemote = new HashSet<>(plan.toDeleteRemote);
            int position = 0;
            long newestRemoteTime = System.currentTimeMillis();
            for (Map.Entry<Integer, JSONObject> entry : remoteItems.entrySet()) {
                if (deletingRemote.contains(entry.getKey())) continue;
                if (localIds.contains(entry.getKey())) {
                    Queries.FavoriteTable.refreshFavoriteListItem(entry.getValue());
                } else {
                    Queries.FavoriteTable.addFavoriteListItem(entry.getValue(), newestRemoteTime - position);
                    position++;
                }
            }

            Tally tally = new Tally();
            int completed = 0;
            int totalWrites = plan.toUpload.size() + plan.toDeleteRemote.size();

            for (int id : plan.toUpload) {
                WriteOutcome outcome = write(context, id, true);
                if (outcome == WriteOutcome.DISABLED)
                    throw new IOException("Favorites are disabled server-side");
                if (outcome == WriteOutcome.EXPIRED)
                    throw new IOException("Session expired mid-sync");
                // Deliberately does not touch the sort time. Pushing an existing local favorite
                // to the account is reconciliation, not re-favoriting it, so it should stay where
                // the user left it - otherwise the list visibly reshuffles one row per request.
                tally.recordUpload(outcome);
                if (outcome == WriteOutcome.GONE) {
                    // The gallery no longer exists upstream; drop it rather than retry forever.
                    Queries.FavoriteTable.removeFavorite(id);
                    localIds.remove(id);
                }
                listener.onProgress(true, ++completed, totalWrites);
            }

            for (int id : plan.toDeleteRemote) {
                WriteOutcome outcome = write(context, id, false);
                if (outcome == WriteOutcome.DISABLED)
                    throw new IOException("Favorites are disabled server-side");
                if (outcome == WriteOutcome.EXPIRED)
                    throw new IOException("Session expired mid-sync");
                tally.recordRemoteDelete(outcome);
                listener.onProgress(true, ++completed, totalWrites);
            }

            for (int id : plan.toDeleteLocal) {
                Queries.FavoriteTable.removeFavorite(id);
                localIds.remove(id);
            }

            // The reconciled set is what both sides should now hold. localIds already tracks every
            // local change made above - downloads aside - including galleries dropped because they
            // 404'd, which must not survive into the baseline as ids neither side holds.
            Set<Integer> reconciled = new HashSet<>(localIds);
            reconciled.addAll(plan.toDownload);

            // Only advance the baseline when nothing failed. A baseline recorded over a partial
            // run would describe a state neither side is in, and the next sync would read those
            // gaps as deletions.
            if (tally.failed == 0) {
                Queries.FavoriteSyncBaselineTable.replaceBaseline(reconciled);
                markBaselineReady(context);
            }

            listener.onComplete(new Result(plan.toDownload.size(), tally.uploaded,
                plan.toDeleteLocal.size(), tally.removedRemote, tally.failed, !hasBaseline));
        } catch (IOException | JSONException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LogUtility.e("Favorite sync failed", e);
            listener.onFailure();
        }
    }

    /**
     * Decides what each side needs, following the table in the class comment.
     * <p>
     * Deliberately free of Android, database and network types: a wrong branch here silently
     * corrupts both libraries and is invisible in a build that only compiles, so this is the part
     * that has to stay directly exercisable by a plain unit test.
     *
     * @param localIdsOldestFirst oldest first, so uploads go out in the order they were favorited
     * @param hasBaseline         false on a first sync, where deletions cannot be told from
     *                            additions and the plan must therefore be a pure union
     */
    static Plan plan(@NonNull Set<Integer> remoteIds, @NonNull List<Integer> localIdsOldestFirst,
                     @NonNull Set<Integer> baseline, boolean hasBaseline) {
        Plan plan = new Plan();
        Set<Integer> localIds = new HashSet<>(localIdsOldestFirst);
        for (int id : remoteIds) {
            if (localIds.contains(id)) continue;
            // Known to the baseline but gone locally means it was unfavorited in the app.
            if (hasBaseline && baseline.contains(id)) plan.toDeleteRemote.add(id);
            else plan.toDownload.add(id);
        }
        for (int id : localIdsOldestFirst) {
            if (remoteIds.contains(id)) continue;
            if (hasBaseline && baseline.contains(id)) plan.toDeleteLocal.add(id);
            else plan.toUpload.add(id);
        }
        return plan;
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
            // The credential is dead - ApiAuthInterceptor has already cleared it - so every
            // remaining write in this run would be rejected the same way.
            if (response.code() == 401 || response.code() == 403) return WriteOutcome.EXPIRED;
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

    enum WriteOutcome {OK, FAILED, GONE, DISABLED, EXPIRED}

    /**
     * What a sync decided to do, before anything has been written.
     */
    static final class Plan {
        final List<Integer> toDownload = new ArrayList<>();
        final List<Integer> toUpload = new ArrayList<>();
        final List<Integer> toDeleteLocal = new ArrayList<>();
        final List<Integer> toDeleteRemote = new ArrayList<>();
    }

    /**
     * What the write phase actually achieved, which is not the same as what it attempted. Kept
     * apart from the loops because {@link #runSync} withholds the baseline on {@code failed > 0},
     * so miscounting a success as a failure disables deletion reconciliation outright rather than
     * producing any visible error.
     */
    static final class Tally {
        int uploaded, removedRemote, dropped, failed;

        /**
         * @param outcome of a POST favorite; DISABLED aborts the run and never reaches here
         */
        void recordUpload(WriteOutcome outcome) {
            if (outcome == WriteOutcome.OK) uploaded++;
                // Gone upstream: dropping it locally *is* the resolution, so it must not hold the
                // baseline back the way a genuine failure does.
            else if (outcome == WriteOutcome.GONE) dropped++;
            else failed++;
        }

        /**
         * @param outcome of a DELETE favorite; a gallery already gone upstream counts as removed
         */
        void recordRemoteDelete(WriteOutcome outcome) {
            if (outcome == WriteOutcome.FAILED) failed++;
            else removedRemote++;
        }
    }

    public static final class Progress {
        /**
         * False while reading the account, true once favorites are being added or removed.
         */
        public final boolean writing;
        public final int completed;
        /**
         * Zero when the total is not known yet, which the UI should show as indeterminate.
         */
        public final int total;

        Progress(boolean writing, int completed, int total) {
            this.writing = writing;
            this.completed = completed;
            this.total = total;
        }
    }

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
