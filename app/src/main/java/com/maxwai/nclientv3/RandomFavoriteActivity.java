package com.maxwai.nclientv3;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GalleryData;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.settings.Favorites;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.net.HttpURLConnection;
import java.util.Objects;

public class RandomFavoriteActivity extends GeneralActivity {

    private Gallery loadedGallery = null;
    private TextView language;
    private ImageButton thumbnail;
    private ImageButton favorite;
    private TextView title;
    private TextView page;
    private View censor;
    private boolean isFavorite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_random);

        Toolbar toolbar = findViewById(R.id.toolbar);
        FloatingActionButton shuffle = findViewById(R.id.shuffle);
        ImageButton share = findViewById(R.id.share);
        censor = findViewById(R.id.censor);
        language = findViewById(R.id.language);
        thumbnail = findViewById(R.id.thumbnail);
        favorite = findViewById(R.id.favorite);
        title = findViewById(R.id.title);
        page = findViewById(R.id.pages);

        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.random_favorite);

        loadRandomGallery();

        shuffle.setOnClickListener(v -> loadRandomGallery());

        thumbnail.setOnClickListener(v -> {
            if (loadedGallery != null) {
                Intent intent = new Intent(this, GalleryActivity.class);
                intent.putExtra(getPackageName() + ".GALLERY", loadedGallery);
                startActivity(intent);
            }
        });

        share.setOnClickListener(v -> {
            if (loadedGallery != null) Global.shareGallery(this, loadedGallery);
        });

        censor.setOnClickListener(v -> censor.setVisibility(View.GONE));

        favorite.setOnClickListener(v -> {
            if (loadedGallery != null) {
                if (isFavorite) {
                    Favorites.removeFavorite(loadedGallery);
                    isFavorite = false;
                } else {
                    Favorites.addFavorite(loadedGallery);
                    isFavorite = true;
                }
                updateFavoriteButton();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadRandomGallery() {
        new Thread(() -> {
            int total = Queries.FavoriteTable.countFavorite();
            if (total < 1) return;
            int randomIndex = Utility.RANDOM.nextInt(total);
            Cursor c = Queries.FavoriteTable.getAllFavoriteGalleriesCursor("", false, 1, randomIndex);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                try {
                    // Check for old-format pages before constructing Gallery to avoid readPagePath's implicit thread
                    int pagesIndex = c.getColumnIndex(Queries.GalleryTable.PAGES);
                    String pagesStr = pagesIndex >= 0 ? c.getString(pagesIndex) : null;
                    boolean isOldFormat = pagesStr != null && pagesStr.contains(";") && !pagesStr.contains("/");
                    int idIndex = c.getColumnIndex(Queries.GalleryTable.IDGALLERY);
                    if (idIndex < 0) {
                        LogUtility.w("Gallery ID column not found in cursor");
                        c.close();
                        return;
                    }
                    int galleryId = c.getInt(idIndex);

                    if (isOldFormat) {
                        GalleryData.queuedForRefresh.add(galleryId);
                        try {
                            String url = Utility.getBaseUrl() + "api/v2/galleries/" + galleryId;
                            try (okhttp3.Response resp = Global.getClient(this).newCall(
                                    new okhttp3.Request.Builder().url(url).build()).execute()) {
                                okhttp3.ResponseBody respBody = resp.body();
                                String body = respBody != null ? respBody.string() : "";
                                if (resp.code() == HttpURLConnection.HTTP_OK) {
                                    Gallery gallery = new Gallery(this, body, null, false);
                                    Queries.GalleryTable.insert(gallery);
                                    runOnUiThread(() -> loadGallery(gallery));
                                    return;
                                } else {
                                    LogUtility.w("Random: old-format fetch failed for " + galleryId + " (HTTP " + resp.code() + "), using cached data");
                                }
                            }
                        } catch (Exception e) {
                            LogUtility.e("Random: old-format fetch failed for " + galleryId + ": " + e.getMessage(), e);
                        } finally {
                            GalleryData.queuedForRefresh.remove(galleryId);
                        }
                    }

                    Gallery g = Queries.GalleryTable.cursorToGallery(RandomFavoriteActivity.this, c);
                    runOnUiThread(() -> loadGallery(g));
                    // If readPagePath started a background thread (old-format fallback), reload UI when it finishes
                    g.getGalleryData().setOnRefreshed(() -> {
                        Queries.GalleryTable.insert(g);
                        runOnUiThread(() -> loadGallery(g));
                    });
                } finally {
                    c.close();
                }
            }
        }).start();
    }

    private void loadGallery(Gallery gallery) {
        loadedGallery = gallery;
        if (Global.isDestroyed(this)) return;
        ImageDownloadUtility.loadImage(this, gallery.getThumbnail(), thumbnail);
        language.setText(Global.getLanguageFlag(gallery.getLanguage()));
        isFavorite = Favorites.isFavorite(loadedGallery);
        updateFavoriteButton();
        title.setText(gallery.getTitle());
        page.setText(getString(R.string.page_count_format, gallery.getPageCount()));
        censor.setVisibility(gallery.hasIgnoredTags() ? View.VISIBLE : View.GONE);
    }

    private void updateFavoriteButton() {
        runOnUiThread(() -> {
            ImageDownloadUtility.loadImage(isFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, favorite);
            Global.setTint(this, favorite.getDrawable());
        });
    }
}
