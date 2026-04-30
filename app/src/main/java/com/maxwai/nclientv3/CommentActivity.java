package com.maxwai.nclientv3;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;

import com.maxwai.nclientv3.adapters.CommentAdapter;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.api.comments.CommentCountFetcher;
import com.maxwai.nclientv3.api.comments.CommentsFetcher;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.views.PageSwitcher;
import com.maxwai.nclientv3.settings.AuthRequest;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;


public class CommentActivity extends BaseActivity {
    private static final int MINIUM_MESSAGE_LENGHT = 10;
    private CommentAdapter adapter;
    private PageSwitcher pageSwitcher;
    private int galleryId;
    private List<Comment> allComments;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_comment);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.comments);
        int id = getIntent().getIntExtra(getPackageName() + ".GALLERYID", -1);
        if (id == -1) {
            finish();
            return;
        }
        galleryId = id;
        pageSwitcher = findViewById(R.id.page_switcher);
        pageSwitcher.setChanger(new PageSwitcher.DefaultPageChanger() {
            @Override
            public void pageChanged() {
                refreshCurrentPage();
                recycler.scrollToPosition(0);
            }
        });
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        refresher.setOnRefreshListener(() -> {
            pageSwitcher.setActualPage(1);
            loadComments(1, true);
        });
        EditText commentText = findViewById(R.id.commentText);
        findViewById(R.id.card).setVisibility(Login.isLogged() ? View.VISIBLE : View.GONE);
        findViewById(R.id.sendButton).setOnClickListener(v -> {
            if (commentText.getText().toString().length() < MINIUM_MESSAGE_LENGHT) {
                Toast.makeText(this, getString(R.string.minimum_comment_length, MINIUM_MESSAGE_LENGHT), Toast.LENGTH_SHORT).show();
                return;
            }
            String refererUrl = String.format(Locale.US, Utility.getBaseUrl() + "g/%d/", galleryId);
            String submitUrl = String.format(Locale.US, Utility.getBaseUrl() + "api/v2/galleries/%d/comments/submit", galleryId);
            String requestString = createRequestString(commentText.getText().toString());
            commentText.setText("");
            RequestBody body = RequestBody.create(requestString, MediaType.get("application/json"));
            new AuthRequest(refererUrl, submitUrl, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {

                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (JsonReader reader = new JsonReader(response.body().charStream())) {
                        Comment comment = null;
                        reader.beginObject();
                        while (reader.peek() != JsonToken.END_OBJECT) {
                            if ("comment".equals(reader.nextName())) {
                                comment = new Comment(reader);
                            } else {
                                reader.skipValue();
                            }
                        }
                        if (comment != null && adapter != null) {
                            if (allComments == null) {
                                allComments = new ArrayList<>();
                            }
                            allComments.add(0, comment);
                            adapter.addComment(comment);
                            if (pageSwitcher.getActualPage() > 1) {
                                pageSwitcher.setActualPage(1);
                            }
                        }
                    }
                }
            }).setMethod("POST", body).start();
        });
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        recycler.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        refresher.setRefreshing(true);
        new CommentCountFetcher(CommentActivity.this, id).start();
        loadComments(1, true);
    }

    private void loadComments(int page, boolean fetchAll) {
        new CommentsFetcher(CommentActivity.this, galleryId, page, fetchAll).start();
    }

    private void loadComments(int page) {
        loadComments(page, false);
    }

    public void refreshCurrentPage() {
        loadComments(pageSwitcher.getActualPage(), false);
    }

    public void removeComment(int commentId) {
        if (allComments != null) {
            allComments.removeIf(c -> c.getId() == commentId);
        }
    }

    public void setAllComments(List<Comment> comments) {
        this.allComments = comments;
    }

    public List<Comment> getAllComments() {
        return allComments;
    }

    public void updatePagination(int totalPages) {
        if (totalPages > 0) {
            pageSwitcher.setTotalPage(totalPages);
        }
    }

    public void setAdapter(CommentAdapter adapter) {
        this.adapter = adapter;
    }

    private String createRequestString(String text) {
        try (StringWriter writer = new StringWriter();
             JsonWriter json = new JsonWriter(writer)) {
            json.beginObject();
            json.name("body").value(text);
            json.endObject();
            return writer.toString();
        } catch (IOException ignore) {
        }
        return "";
    }

    @Override
    protected int getPortraitColumnCount() {
        return 1;
    }

    @Override
    protected int getLandscapeColumnCount() {
        return 2;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
