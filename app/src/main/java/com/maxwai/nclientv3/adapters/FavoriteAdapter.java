package com.maxwai.nclientv3.adapters;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.text.Layout;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.maxwai.nclientv3.FavoriteActivity;
import com.maxwai.nclientv3.GalleryActivity;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LogUtility;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FavoriteAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> implements Filterable {
    private final int perPage = FavoriteActivity.getEntryPerPage();
    private final SparseIntArray statuses = new SparseIntArray();
    private final FavoriteActivity activity;
    private GenericGallery[] galleries;
    private final Set<Integer> loadingGalleryIds = Collections.synchronizedSet(new HashSet<>());
    private CharSequence lastQuery;
    private Cursor cursor;
    private boolean force = false;
    private boolean sortByTitle = false;

    public FavoriteAdapter(FavoriteActivity activity) {
        this.activity = activity;
        this.lastQuery = "";
        setHasStableIds(true);
    }

    @SuppressLint("Range")
    @Override
    public long getItemId(int position) {
        cursor.moveToPosition(position);
        return cursor.getInt(cursor.getColumnIndex(Queries.GalleryTable.IDGALLERY));
    }

    @NonNull
    @Override
    public GenericAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new GenericAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.entry_layout, parent, false));
    }

    @Nullable
    private GenericGallery galleryFromPosition(int position) {
        if (galleries[position] != null) return galleries[position];
        cursor.moveToPosition(position);
        String pageData = cursor.getString(
            cursor.getColumnIndex(Queries.GalleryTable.PAGES));
        if (pageData != null && pageData.startsWith(Queries.GalleryTable.FAVORITE_SUMMARY_PREFIX)) {
            try {
                GenericGallery summary = SimpleGallery.fromV2ListItem(activity, new JSONObject(
                    pageData.substring(Queries.GalleryTable.FAVORITE_SUMMARY_PREFIX.length())));
                galleries[position] = summary;
                return summary;
            } catch (JSONException e) {
                LogUtility.e("Unable to read cached favorite summary", e);
                return null;
            }
        }
        if (pageData != null && pageData.contains(";") && !pageData.contains("/")) {
            GenericGallery summary = SimpleGallery.fromLegacyFavoriteCursor(cursor);
            galleries[position] = summary;
            return summary;
        }
        Gallery g = Queries.GalleryTable.cursorToGallery(activity, cursor);
        galleries[position] = g;
        if (g.getGalleryData().hasUpdatedInfo()) { // TODO: to be removed in next major version
            if (g.getGalleryData().isDeleted()) {
                LogUtility.w("Deleting Gallery " + g.getTitle() + " with id " + g.getId() + " since not available anymore");
                Queries.GalleryTable.delete(g.getId());
            } else {
                Queries.GalleryTable.insert(g);
            }
        }
        return g;
    }

    @Override
    public void onBindViewHolder(@NonNull final GenericAdapter.ViewHolder holder, int position) {
        final GenericGallery ent = galleryFromPosition(holder.getBindingAdapterPosition());
        if (ent == null) return;
        if (ent instanceof Gallery)
            ImageDownloadUtility.loadImage(activity, ((Gallery) ent).getThumbnail(), holder.imgView);
        else
            ImageDownloadUtility.loadImage(activity, ((SimpleGallery) ent).getThumbnail(), holder.imgView);
        holder.pages.setText(String.format(Locale.US, "%d", ent.getPageCount()));
        holder.title.setText(ent.getTitle());
        holder.flag.setText(Global.getLanguageFlag(ent instanceof Gallery
            ? ((Gallery) ent).getLanguage() : ((SimpleGallery) ent).getLanguage()));
        holder.title.setOnClickListener(v -> {
            Layout layout = holder.title.getLayout();
            if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0)
                holder.title.setMaxLines(7);
            else if (holder.title.getMaxLines() == 7) holder.title.setMaxLines(3);
            else holder.layout.performClick();
        });
        holder.layout.setOnClickListener(v -> {
            if (ent.isValid()) openGallery(ent);
        });
        holder.layout.setOnLongClickListener(v -> {
            holder.title.animate().alpha(holder.title.getAlpha() == 0f ? 1f : 0f).setDuration(100).start();
            holder.flag.animate().alpha(holder.flag.getAlpha() == 0f ? 1f : 0f).setDuration(100).start();
            holder.pages.animate().alpha(holder.pages.getAlpha() == 0f ? 1f : 0f).setDuration(100).start();
            return true;
        });
        int statusColor = statuses.get(ent.getId(), 0);
        if (statusColor == 0) {
            statusColor = Queries.StatusMangaTable.getStatus(ent.getId()).color;
            statuses.put(ent.getId(), statusColor);
        }
        holder.title.setBackgroundColor(statusColor);
    }

    private void openGallery(GenericGallery ent) {
        if (ent instanceof Gallery) {
            startGallery((Gallery) ent);
            return;
        }
        if (!loadingGalleryIds.add(ent.getId())) return;
        InspectorV3.galleryInspector(activity, ent.getId(), new InspectorV3.DefaultInspectorResponse() {
            @Override
            public void onSuccess(List<GenericGallery> results) {
                loadingGalleryIds.remove(ent.getId());
                if (results == null || results.size() != 1 || !(results.get(0) instanceof Gallery)) {
                    showLoadFailure();
                    return;
                }
                Gallery gallery = (Gallery) results.get(0);
                Queries.FavoriteTable.addFavorite(gallery);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    replaceGallery(gallery);
                    startGallery(gallery);
                });
            }

            @Override
            public void onFailure(Exception e) {
                loadingGalleryIds.remove(ent.getId());
                LogUtility.e("Unable to load favorite gallery detail", e);
                showLoadFailure();
            }
        }).start();
    }

    private void showLoadFailure() {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            Toast.makeText(activity,
                R.string.unable_to_connect_to_the_site, Toast.LENGTH_SHORT).show();
        });
    }

    private void replaceGallery(Gallery gallery) {
        if (galleries == null) return;
        for (int i = 0; i < galleries.length; i++) {
            if (galleries[i] != null && galleries[i].getId() == gallery.getId()) {
                galleries[i] = gallery;
                notifyItemChanged(i);
                return;
            }
        }
    }

    private void startGallery(Gallery ent) {
        Intent intent = new Intent(activity, GalleryActivity.class);
        LogUtility.d(ent + "");
        intent.putExtra(activity.getPackageName() + ".GALLERY", ent);
        intent.putExtra(activity.getPackageName() + ".UNKNOWN", true);
        activity.startActivity(intent);
    }

    public void changePage() {
        forceReload();
    }

    @Override
    public int getItemCount() {
        return cursor == null ? 0 : cursor.getCount();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                constraint = constraint.toString().toLowerCase(Locale.US);
                if ((!force && lastQuery.equals(constraint))) return null;
                LogUtility.d("FILTERING");
                setRefresh(true);
                FilterResults results = new FilterResults();
                lastQuery = constraint.toString();
                LogUtility.d(lastQuery + "LASTQERY");
                force = false;
                Cursor c = Queries.FavoriteTable.getAllFavoriteGalleriesCursor(lastQuery, sortByTitle, perPage, (activity.getActualPage() - 1) * perPage);
                results.count = c.getCount();
                results.values = c;
                LogUtility.d("FILTERING3");
                LogUtility.d(results.count + ";" + results.values);
                setRefresh(false);
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                if (results == null) return;
                setRefresh(true);
                final int oldSize = getItemCount(), newSize = results.count;
                updateCursor((Cursor) results.values);
                //not in runOnUIThread because is always executed on UI
                if (oldSize > newSize) notifyItemRangeRemoved(newSize, oldSize - newSize);
                else notifyItemRangeInserted(oldSize, newSize - oldSize);
                notifyItemRangeChanged(0, Math.min(newSize, oldSize));

                setRefresh(false);
            }
        };
    }

    public void setSortByTitle(boolean sortByTitle) {
        this.sortByTitle = sortByTitle;
        forceReload();
    }

    public void forceReload() {
        force = true;
        activity.runOnUiThread(() -> getFilter().filter(lastQuery));
    }

    public void setRefresh(boolean refresh) {
        activity.runOnUiThread(() -> activity.getRefresher().setRefreshing(refresh));
    }

    private void updateCursor(@Nullable Cursor c) {
        if (cursor != null) cursor.close();
        galleries = new GenericGallery[c == null ? 0 : c.getCount()];
        cursor = c;
        statuses.clear();
    }

    public Collection<Gallery> getAllGalleries() {
        if (cursor == null) return Collections.emptyList();
        int count = cursor.getCount();
        ArrayList<Gallery> galleries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenericGallery gallery = galleryFromPosition(i);
            if (gallery instanceof Gallery) galleries.add((Gallery) gallery);
        }
        return galleries;
    }


}
