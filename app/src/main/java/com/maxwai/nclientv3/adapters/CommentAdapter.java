package com.maxwai.nclientv3.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.comments.Comment;
import com.maxwai.nclientv3.settings.AuthRequest;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.ViewHolder> {
    private final List<Comment> comments;
    private final DateFormat format;
    private final int userId;
    private final int galleryId;
    private final AppCompatActivity context;

    public CommentAdapter(AppCompatActivity context, List<Comment> comments, int galleryId) {
        this.context = context;
        format = android.text.format.DateFormat.getDateFormat(context);
        this.galleryId = galleryId;
        this.comments = comments == null ? new ArrayList<>() : new ArrayList<>(comments);
        if (Login.isLogged() && Login.getUser() != null) {
            userId = Login.getUser().getId();
        } else userId = -1;
    }

    @NonNull
    @Override
    public CommentAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.comment_layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull CommentAdapter.ViewHolder holder, int pos) {
        Comment c = comments.get(pos);
        holder.layout.setOnClickListener(v1 -> context.runOnUiThread(() -> holder.body.setMaxLines(holder.body.getMaxLines() == 7 ? 999 : 7)));
        holder.close.setVisibility(c.getPosterId() != userId ? View.GONE : View.VISIBLE);
        holder.user.setText(c.getUsername());
        // Name and avatar both open the poster's profile, as on the website.
        View.OnClickListener openProfile = v -> com.maxwai.nclientv3.UserProfileActivity.start(
            context, c.getPosterId(), c.getPosterSlug(), c.getUsername());
        holder.user.setOnClickListener(openProfile);
        holder.userImage.setOnClickListener(openProfile);
        holder.layout.setOnLongClickListener(v -> {
            com.maxwai.nclientv3.UserProfileActivity.start(
                context, c.getPosterId(), c.getPosterSlug(), c.getUsername());
            return true;
        });
        holder.body.setText(c.getComment());
        holder.date.setText(format.format(c.getPostDate()));
        holder.close.setOnClickListener(v -> {
            String refererUrl = String.format(Locale.US, Utility.getBaseUrl() + "g/%d/", galleryId);
            String submitUrl = String.format(Locale.US, Utility.getBaseUrl() + "api/v2/comments/%d/delete", c.getId());
            new AuthRequest(refererUrl, submitUrl, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {

                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (response) {
                        ResponseBody body = response.body();
                        if (body == null || !body.string().contains("true")) return;
                    }
                    if (context instanceof com.maxwai.nclientv3.CommentActivity) {
                        ((com.maxwai.nclientv3.CommentActivity) context).removeComment(c.getId());
                    }
                    // Locate the row by id on the UI thread rather than reusing the position this
                    // holder was bound at: an insert from addComment shifts every later row, so
                    // by the time the response lands that index can name a different comment.
                    // Mutating the list here also keeps the write on the thread that reads it.
                    context.runOnUiThread(() -> {
                        int index = indexOfComment(c.getId());
                        if (index < 0) return;
                        comments.remove(index);
                        notifyItemRemoved(index);
                    });
                }
            }).setMethod("POST", AuthRequest.EMPTY_BODY).start();
        });
        if (c.getAvatarUrl() == null || Global.getDownloadPolicy() != Global.DataUsageType.FULL)
            ImageDownloadUtility.loadImage(R.drawable.ic_person, holder.userImage);
        else
            ImageDownloadUtility.loadImage(context, c.getAvatarUrl(), holder.userImage);
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    public void addComment(Comment c) {
        context.runOnUiThread(() -> {
            comments.add(0, c);
            notifyItemInserted(0);
        });
    }

    private int indexOfComment(int id) {
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).getId() == id) return i;
        }
        return -1;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageButton userImage;
        final ImageButton close;
        final TextView user;
        final TextView body;
        final TextView date;
        final ConstraintLayout layout;

        public ViewHolder(@NonNull View v) {
            super(v);
            layout = v.findViewById(R.id.master_layout);
            userImage = v.findViewById(R.id.propic);
            close = v.findViewById(R.id.close);
            user = v.findViewById(R.id.username);
            body = v.findViewById(R.id.body);
            date = v.findViewById(R.id.date);
        }
    }
}
