package com.maxwai.nclientv3.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.users.UserProfile;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

public class RecentCommentAdapter extends RecyclerView.Adapter<RecentCommentAdapter.ViewHolder> {
    private final List<UserProfile.RecentComment> comments;
    private final DateFormat format;
    private final RecentFavoriteAdapter.OnGalleryClick listener;

    public RecentCommentAdapter(AppCompatActivity context, List<UserProfile.RecentComment> comments,
                                RecentFavoriteAdapter.OnGalleryClick listener) {
        this.comments = comments == null ? new ArrayList<>() : new ArrayList<>(comments);
        this.format = android.text.format.DateFormat.getDateFormat(context);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_recent_comment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UserProfile.RecentComment comment = comments.get(position);
        holder.galleryTitle.setText(comment.getGalleryTitle());
        holder.body.setText(comment.getBody());
        holder.date.setText(comment.getPostDate() == null ? "" : format.format(comment.getPostDate()));
        // Tapping a comment opens the gallery it was posted on, as on the website.
        holder.master.setOnClickListener(v -> listener.onGalleryClick(comment.getGalleryId()));
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View master;
        final TextView galleryTitle, body, date;

        ViewHolder(@NonNull View v) {
            super(v);
            master = v.findViewById(R.id.master);
            galleryTitle = v.findViewById(R.id.gallery_title);
            body = v.findViewById(R.id.body);
            date = v.findViewById(R.id.date);
        }
    }
}
