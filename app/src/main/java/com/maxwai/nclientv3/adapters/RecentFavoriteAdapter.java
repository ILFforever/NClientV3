package com.maxwai.nclientv3.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.users.UserProfile;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;

import java.util.ArrayList;
import java.util.List;

public class RecentFavoriteAdapter extends RecyclerView.Adapter<RecentFavoriteAdapter.ViewHolder> {
    private final AppCompatActivity context;
    private final List<UserProfile.RecentFavorite> favorites;
    private final OnGalleryClick listener;

    public RecentFavoriteAdapter(AppCompatActivity context, List<UserProfile.RecentFavorite> favorites,
                                 OnGalleryClick listener) {
        this.context = context;
        this.favorites = favorites == null ? new ArrayList<>() : new ArrayList<>(favorites);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_recent_favorite, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UserProfile.RecentFavorite favorite = favorites.get(position);
        holder.title.setText(favorite.getTitle());
        holder.master.setOnClickListener(v -> listener.onGalleryClick(favorite.getId()));
        if (favorite.getThumbnail() == null || Global.getDownloadPolicy() == Global.DataUsageType.NONE)
            ImageDownloadUtility.loadImage(R.mipmap.ic_launcher, holder.thumbnail);
        else
            ImageDownloadUtility.loadImage(context, favorite.getThumbnail(), holder.thumbnail);
    }

    @Override
    public int getItemCount() {
        return favorites.size();
    }

    public interface OnGalleryClick {
        void onGalleryClick(int galleryId);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View master;
        final ImageView thumbnail;
        final TextView title;

        ViewHolder(@NonNull View v) {
            super(v);
            master = v.findViewById(R.id.master);
            thumbnail = v.findViewById(R.id.thumbnail);
            title = v.findViewById(R.id.title);
        }
    }
}
