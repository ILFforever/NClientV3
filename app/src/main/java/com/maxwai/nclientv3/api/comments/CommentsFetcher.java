package com.maxwai.nclientv3.api.comments;

import android.util.JsonReader;
import android.util.JsonToken;

import com.maxwai.nclientv3.CommentActivity;
import com.maxwai.nclientv3.adapters.CommentAdapter;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommentsFetcher extends Thread {
    private static final String COMMENT_API_URL = Utility.getBaseUrl() + "api/v2/galleries/%d/comments?page=%d&per_page=%d";
    /**
     * The API caps per_page at 50, so pagination has to follow the same step.
     */
    public static final int COMMENTS_PER_PAGE = 50;
    private final int id;
    private final CommentActivity commentActivity;
    private final int page;
    private final List<Comment> comments = new ArrayList<>();
    private int numPages = -1;

    public CommentsFetcher(CommentActivity commentActivity, int id) {
        this(commentActivity, id, 1);
    }

    public CommentsFetcher(CommentActivity commentActivity, int id, int page) {
        this.id = id;
        this.commentActivity = commentActivity;
        this.page = Math.max(1, page);
    }

    @Override
    public void run() {
        populateComments();
        postResult();
    }

    private void postResult() {
        commentActivity.setComments(comments);
        CommentAdapter commentAdapter = new CommentAdapter(commentActivity, comments, id);
        commentActivity.setAdapter(commentAdapter);
        commentActivity.runOnUiThread(() -> {
            commentActivity.getRecycler().setAdapter(commentAdapter);
            commentActivity.getRefresher().setRefreshing(false);
            if (numPages > 0) commentActivity.updatePagination(numPages);
        });
    }

    private void populateComments() {
        String url = String.format(Locale.US, COMMENT_API_URL, id, page, COMMENTS_PER_PAGE);
        LogUtility.d("Fetching comments for gallery:", id, "page:", page);
        try (Response response = Objects.requireNonNull(Global.getClient()).newCall(new Request.Builder().url(url).build()).execute()) {
            LogUtility.d("Comments response code:", response.code());
            ResponseBody body = response.body();
            if (body == null) {
                LogUtility.e("Response body is null");
                return;
            }
            if (!response.isSuccessful()) {
                LogUtility.e("Comments request failed:", response.code(), body.string());
                return;
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(body.byteStream()))) {
                readPage(reader);
            }
            LogUtility.d("Loaded", comments.size(), "comments of", numPages, "pages");
        } catch (NullPointerException | IOException e) {
            LogUtility.e("Error getting comments", e);
        }
    }

    /**
     * Reads the paginated envelope: {"result": [...], "num_pages": n, "per_page": n, "total": n}
     */
    private void readPage(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            LogUtility.e("Unexpected JSON token:", reader.peek());
            return;
        }
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "result":
                    reader.beginArray();
                    while (reader.hasNext())
                        comments.add(new Comment(reader));
                    reader.endArray();
                    break;
                case "num_pages":
                    numPages = reader.nextInt();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
    }
}
