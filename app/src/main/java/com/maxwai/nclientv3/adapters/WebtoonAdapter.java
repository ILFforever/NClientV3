package com.maxwai.nclientv3.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.GlideX;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.files.PageFile;
import com.maxwai.nclientv3.github.chrisbanes.photoview.PhotoView;
import com.maxwai.nclientv3.utility.LogUtility;

import java.util.Objects;

public class WebtoonAdapter extends RecyclerView.Adapter<WebtoonAdapter.ViewHolder> {
    private final Context context;
    private final GenericGallery gallery;
    private final GalleryFolder directory;
    private final SparseIntArray rotations = new SparseIntArray();
    private View.OnClickListener clickListener;

    public WebtoonAdapter(Context context, GenericGallery gallery, GalleryFolder directory) {
        this.context = context;
        this.gallery = Objects.requireNonNull(gallery, "Gallery cannot be null");
        this.directory = directory;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_webtoon_page, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.retryButton.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) loadImage(holder, adapterPosition);
        });
        holder.photoView.setMaximumScale(8.0f);
        holder.photoView.setOnPhotoTapListener((ImageView view, float x, float y) -> {
            if (clickListener != null) clickListener.onClick(view);
        });
        loadImage(holder, position);
    }

    private void loadImage(@NonNull ViewHolder holder, int position) {
        try {
            RequestBuilder<Drawable> request = loadPage(position);
            if (request == null) {
                showRetry(holder);
                return;
            }
            request.transform(new Rotate(rotations.get(position, 0)))
                .apply(new RequestOptions()
                    .override(Target.SIZE_ORIGINAL)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_refresh)
                    .fitCenter())
                .priority(Priority.NORMAL)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean first) {
                        showRetry(holder);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                   DataSource source, boolean first) {
                        holder.photoView.setVisibility(View.VISIBLE);
                        holder.retryButton.setVisibility(View.GONE);
                        if (resource instanceof GifDrawable) ((GifDrawable) resource).start();
                        return false;
                    }
                })
                .into(holder.photoView);
        } catch (Exception e) {
            LogUtility.e("Error loading image for position " + position + ": " + e.getMessage());
            showRetry(holder);
        }
    }

    private void showRetry(@NonNull ViewHolder holder) {
        holder.photoView.setVisibility(View.GONE);
        holder.retryButton.setVisibility(View.VISIBLE);
    }

    private RequestBuilder<Drawable> loadPage(int position) {
        try {
            if (directory != null) {
                PageFile page = directory.getPage(position + 1);
                if (page != null && page.toUri() != null) return GlideX.with(context).load(page.toUri());
            }
            if (!(gallery instanceof Gallery)) return null;
            Uri pageUrl = ((Gallery) gallery).getPageUrl(position);
            return pageUrl == null ? null : GlideX.with(context).load(pageUrl);
        } catch (Exception e) {
            LogUtility.e("Error in loadPage for position " + position + ": " + e.getMessage());
            return null;
        }
    }

    public Drawable getDrawableAtPosition(RecyclerView recyclerView, int position) {
        if (position < 0 || position >= getItemCount()) return null;
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        return holder instanceof ViewHolder ? ((ViewHolder) holder).photoView.getDrawable() : null;
    }

    @Override
    public int getItemCount() {
        return gallery.getPageCount();
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        GlideX.with(context).clear(holder.photoView);
    }

    /**
     * Warms the cache for a page that is not on screen yet. This deliberately does not
     * need a bound ViewHolder: neighbours of the current page usually have none, so a
     * view-based preload would silently do nothing exactly when it is most useful.
     * onBindViewHolder then hits the cache once the page scrolls in.
     */
    public void preloadPage(int position) {
        if (position < 0 || position >= getItemCount()) return;
        RequestBuilder<Drawable> request = loadPage(position);
        if (request == null) return;
        // Must mirror the decode options in loadImage, or the cache key will not match.
        // LOW keeps prefetching from outranking the page the user is actually looking at.
        request.apply(new RequestOptions()
                .override(Target.SIZE_ORIGINAL)
                .fitCenter())
            .priority(Priority.LOW)
            .preload();
    }

    public void rotatePage(int position) {
        rotations.put(position, (rotations.get(position, 0) + 90) % 360);
        notifyItemChanged(position);
    }

    public void setClickListener(View.OnClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final PhotoView photoView;
        final ImageButton retryButton;

        ViewHolder(@NonNull View view) {
            super(view);
            photoView = view.findViewById(R.id.image);
            retryButton = view.findViewById(R.id.retry_button);
        }
    }
}
