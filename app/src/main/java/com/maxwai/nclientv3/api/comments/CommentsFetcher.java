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
    private static final String COMMENT_API_URL = Utility.getBaseUrl() + "api/v2/galleries/%d/comments";
    public static final int COMMENTS_PER_PAGE = 20;
    private final int id;
    private final CommentActivity commentActivity;
    private final int page;
    private final List<Comment> allComments = new ArrayList<>();
    private boolean fetchAll;

    public CommentsFetcher(CommentActivity commentActivity, int id) {
        this(commentActivity, id, 1, true);
    }

    public CommentsFetcher(CommentActivity commentActivity, int id, int page) {
        this(commentActivity, id, page, false);
    }

    public CommentsFetcher(CommentActivity commentActivity, int id, int page, boolean fetchAll) {
        this.id = id;
        this.commentActivity = commentActivity;
        this.page = page;
        this.fetchAll = fetchAll;
    }

    @Override
    public void run() {
        populateComments();
        postResult();
    }

    private void postResult() {
        List<Comment> commentsToShow;
        if (fetchAll) {
            commentsToShow = allComments;
            commentActivity.setAllComments(allComments);
        } else {
            List<Comment> cached = commentActivity.getAllComments();
            if (cached == null || cached.isEmpty()) {
                commentsToShow = allComments;
                commentActivity.setAllComments(allComments);
            } else {
                commentsToShow = getCommentsForPage(cached, page);
            }
        }
        CommentAdapter commentAdapter = new CommentAdapter(commentActivity, commentsToShow, id);
        commentActivity.setAdapter(commentAdapter);
        commentActivity.runOnUiThread(() -> {
            commentActivity.getRecycler().setAdapter(commentAdapter);
            commentActivity.getRefresher().setRefreshing(false);
        });
    }

    private List<Comment> getCommentsForPage(List<Comment> allComments, int page) {
        int fromIndex = (page - 1) * COMMENTS_PER_PAGE;
        int toIndex = Math.min(fromIndex + COMMENTS_PER_PAGE, allComments.size());
        if (fromIndex >= allComments.size()) {
            return new ArrayList<>();
        }
        return allComments.subList(fromIndex, toIndex);
    }

    private void populateComments() {
        String url = String.format(Locale.US, COMMENT_API_URL, id);
        LogUtility.d("Fetching all comments for gallery:", id);
        try (Response response = Objects.requireNonNull(Global.getClient()).newCall(new Request.Builder().url(url).build()).execute()) {
            LogUtility.d("Comments response code:", response.code());
            ResponseBody body = response.body();
            if (body == null) {
                LogUtility.e("Response body is null");
                return;
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(body.byteStream()))) {
                if(reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    while (reader.hasNext())
                        allComments.add(new Comment(reader));
                    LogUtility.d("Loaded", allComments.size(), "total comments");
                } else {
                    LogUtility.e("Unexpected JSON token:", reader.peek());
                    String rawResponse = body.string();
                    LogUtility.e("Raw response:", rawResponse);
                }
            }
        } catch (NullPointerException | IOException e) {
            LogUtility.e("Error getting comments", e);
        }
    }
}
