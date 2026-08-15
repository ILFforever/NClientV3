package com.maxwai.nclientv3.api.comments;

import com.maxwai.nclientv3.CommentActivity;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommentCountFetcher extends Thread {
    private static final String COUNT_API_URL = Utility.getBaseUrl() + "api/v2/galleries/%d/comments/count";
    private final CommentActivity commentActivity;
    private final int id;

    public CommentCountFetcher(CommentActivity commentActivity, int id) {
        this.id = id;
        this.commentActivity = commentActivity;
    }

    private void fetchCount() {
        String url = String.format(Locale.US, COUNT_API_URL, id);
        LogUtility.d("Fetching comment count for gallery:", id);
        try (Response response = Objects.requireNonNull(Global.getClient())
            .newCall(new Request.Builder().url(url).build()).execute()) {
            LogUtility.d("Comment count response code:", response.code());
            ResponseBody body = response.body();
            if (body == null) return;
            String value = body.string();
            LogUtility.d("Comment count response:", value);
            if (value.contains("error")) {
                LogUtility.w("Gallery not found:", id);
                return;
            }
            try {
                int count = Integer.parseInt(value.trim());
                int totalPages = (int) Math.ceil(count / (double) CommentsFetcher.COMMENTS_PER_PAGE);
                LogUtility.d("Comment count:", count, "Total pages:", totalPages);
                commentActivity.runOnUiThread(() -> commentActivity.updatePagination(totalPages));
            } catch (NumberFormatException e) {
                LogUtility.w("Error parsing comment count:", value);
            }
        } catch (IOException | NullPointerException e) {
            LogUtility.e("Error getting comment count", e);
        }
    }

    @Override
    public void run() {
        fetchCount();
    }
}
