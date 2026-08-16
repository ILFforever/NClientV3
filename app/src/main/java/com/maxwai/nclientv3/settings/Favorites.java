package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Favorites {
    /**
     * How far a favorite change actually got. The distinction matters because two of these are
     * successes that did not reach the account, and telling the user "failed" for those would be
     * wrong - the gallery is favorited, just only here.
     */
    public enum Outcome {
        /**
         * Written to the account and mirrored locally.
         */
        SYNCED,
        /**
         * Saved on this device only; no account is connected.
         */
        LOCAL_ONLY,
        /**
         * Saved on this device only, because the account session had expired. The next sync
         * pushes it up once the user signs in again.
         */
        SESSION_EXPIRED,
        /**
         * Nothing was written anywhere.
         */
        FAILED;

        public boolean isSuccess() {
            return this != FAILED;
        }
    }

    public interface UpdateCallback {
        void onComplete(@NonNull Outcome outcome, boolean favorite);
    }


    public static void addFavorite(Gallery gallery) {
        Queries.FavoriteTable.addFavorite(gallery);
    }

    public static void removeFavorite(GenericGallery gallery) {
        Queries.FavoriteTable.removeFavorite(gallery.getId());
    }

    public static boolean isFavorite(GenericGallery gallery) {
        if (gallery == null || !gallery.isValid()) return false;
        return Queries.FavoriteTable.isFavorite(gallery.getId());
    }

    public static void setFavorite(@NonNull Context context, @NonNull Gallery gallery,
                                   boolean favorite, @NonNull UpdateCallback callback) {
        if (!Login.canAccessAuthenticatedApi(context)) {
            updateLocal(gallery, favorite);
            callback.onComplete(Outcome.LOCAL_ONLY, favorite);
            return;
        }

        String url = String.format(Locale.US, Utility.getApiBaseUrl()
            + "galleries/%d/favorite", gallery.getId());
        Request request = new Request.Builder().url(url)
            .method(favorite ? "POST" : "DELETE", RequestBody.EMPTY)
            .build();
        Global.getClient(context).newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LogUtility.e("Favorite update failed", e);
                completeOnMain(callback, Outcome.FAILED, !favorite);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    // An expired session used to land in the generic failure branch below, which
                    // wrote nothing anywhere: the toggle sprang back and the user simply could not
                    // favorite anything, with no indication why. Save it locally instead - the
                    // request was refused, but the user's intent is still perfectly expressible on
                    // this device, and the next sync after signing in pushes it up.
                    if (response.code() == 401 || response.code() == 403) {
                        LogUtility.d("Favorite refused with HTTP " + response.code()
                            + "; saving locally");
                        Login.onSessionExpired(context);
                        updateLocal(gallery, favorite);
                        completeOnMain(callback, Outcome.SESSION_EXPIRED, favorite);
                        return;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        completeOnMain(callback, Outcome.FAILED, !favorite);
                        return;
                    }
                    boolean remoteFavorite = new JSONObject(response.body().string())
                        .getBoolean("favorited");
                    if (remoteFavorite != favorite) {
                        completeOnMain(callback, Outcome.FAILED, !favorite);
                        return;
                    }
                    updateLocal(gallery, favorite);
                    completeOnMain(callback, Outcome.SYNCED, favorite);
                } catch (Exception e) {
                    LogUtility.e("Favorite response failed", e);
                    completeOnMain(callback, Outcome.FAILED, !favorite);
                }
            }
        });
    }

    /**
     * Turns an outcome into a message, in one place so the favorite buttons scattered across the
     * app cannot describe the same result differently. Says nothing for {@link Outcome#SYNCED} or
     * {@link Outcome#LOCAL_ONLY}: the button already changed state, and neither case needs excusing.
     */
    public static void toastOutcome(@NonNull Context context, @NonNull Outcome outcome) {
        if (outcome == Outcome.SYNCED || outcome == Outcome.LOCAL_ONLY) return;
        Toast.makeText(context, outcome == Outcome.SESSION_EXPIRED
                ? R.string.favorite_saved_session_expired
                : R.string.favorite_update_failed,
            Toast.LENGTH_LONG).show();
    }

    private static void updateLocal(Gallery gallery, boolean favorite) {
        if (favorite) addFavorite(gallery);
        else removeFavorite(gallery);
    }

    private static void completeOnMain(UpdateCallback callback, @NonNull Outcome outcome,
                                       boolean favorite) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(outcome, favorite));
    }

}
