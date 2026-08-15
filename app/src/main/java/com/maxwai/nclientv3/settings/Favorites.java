package com.maxwai.nclientv3.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

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
    public interface UpdateCallback {
        void onComplete(boolean success, boolean favorite);
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
            callback.onComplete(true, favorite);
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
                completeOnMain(callback, false, !favorite);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        completeOnMain(callback, false, !favorite);
                        return;
                    }
                    boolean remoteFavorite = new JSONObject(response.body().string())
                        .getBoolean("favorited");
                    if (remoteFavorite != favorite) {
                        completeOnMain(callback, false, !favorite);
                        return;
                    }
                    updateLocal(gallery, favorite);
                    completeOnMain(callback, true, favorite);
                } catch (Exception e) {
                    LogUtility.e("Favorite response failed", e);
                    completeOnMain(callback, false, !favorite);
                }
            }
        });
    }

    private static void updateLocal(Gallery gallery, boolean favorite) {
        if (favorite) addFavorite(gallery);
        else removeFavorite(gallery);
    }

    private static void completeOnMain(UpdateCallback callback, boolean success, boolean favorite) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(success, favorite));
    }

}
