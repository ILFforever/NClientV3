package com.maxwai.nclientv3;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;

import com.maxwai.nclientv3.adapters.FavoriteAdapter;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.api.components.GalleryData;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.downloader.DownloadGalleryV2;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.views.PageSwitcher;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Objects;


public class FavoriteActivity extends BaseActivity {
    private static final int ENTRY_PER_PAGE = 24;
    private FavoriteAdapter adapter = null;
    private boolean sortByTitle = false;
    private PageSwitcher pageSwitcher;
    private SearchView searchView;
    private volatile boolean legacyQueueRunning = false;

    public static int getEntryPerPage() {
        return Global.isInfiniteScrollFavorite() ? Integer.MAX_VALUE : ENTRY_PER_PAGE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);

        setContentView(R.layout.app_bar_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.favorite_manga);
        pageSwitcher = findViewById(R.id.page_switcher);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        refresher.setRefreshing(true);
        adapter = new FavoriteAdapter(this);


        refresher.setOnRefreshListener(adapter::forceReload);
        adapter.setOnCursorUpdated(this::startLegacyRefreshQueue);
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        recycler.setAdapter(adapter);
        pageSwitcher.setPages(1, 1);
        pageSwitcher.setChanger(new PageSwitcher.DefaultPageChanger() {
            @Override
            public void pageChanged() {
                if (adapter != null) {
                    legacyQueueRunning = false; // cancel current queue; new queue starts after cursor updates
                    adapter.changePage();
                    recycler.scrollToPosition(0);
                }
            }
        });

    }

    public int getActualPage() {
        return pageSwitcher.getActualPage();
    }

@Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandFavorite();
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortFavorite();
    }

    private int calculatePages(@Nullable String text) {
        int perPage = getEntryPerPage();
        int totalEntries = Queries.FavoriteTable.countFavorite(text);
        int div = totalEntries / perPage;
        int mod = totalEntries % perPage;
        return div + (mod == 0 ? 0 : 1);
    }

    @Override
    protected void onResume() {
        refresher.setEnabled(true);
        refresher.setRefreshing(true);
        String query = searchView == null ? null : searchView.getQuery().toString();
        pageSwitcher.setTotalPage(calculatePages(query));
        adapter.forceReload();
        super.onResume();
    }

    private void startLegacyRefreshQueue() {
        if (legacyQueueRunning || adapter == null) return;

        ArrayList<Integer> ids = adapter.getCurrentPageOldFormatIds();
        if (ids.isEmpty()) return;
        legacyQueueRunning = true;
        new Thread(() -> {
            GalleryData.queuedForRefresh.addAll(ids);
            LogUtility.i("Legacy refresh queue: " + ids.size() + " old-format favorites to refresh");
            for (int j = 0; j < ids.size() && legacyQueueRunning; j++) {
                int galleryId = ids.get(j);
                boolean rateLimited = false;
                try {
                    String url = Utility.getBaseUrl() + "api/v2/galleries/" + galleryId;
                    okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
                    try (okhttp3.Response resp = Global.getClient(this).newCall(request).execute()) {
                        okhttp3.ResponseBody respBody = resp.body();
                        String body = respBody != null ? respBody.string() : "";
                        int code = resp.code();
                        if (code == HttpURLConnection.HTTP_OK) {
                            Gallery gallery = new Gallery(this, body, null, false);
                            Queries.GalleryTable.insert(gallery);
                            LogUtility.d("Legacy queue: refreshed gallery " + galleryId);
                            if (adapter != null) adapter.invalidateGallery(galleryId);
                        } else if (code == HttpURLConnection.HTTP_NOT_FOUND || code == 410) {
                            Queries.GalleryTable.delete(galleryId);
                            LogUtility.w("Legacy queue: gallery " + galleryId + " deleted from server (HTTP " + code + ")");
                            if (adapter != null) adapter.invalidateGallery(galleryId);
                        } else if (code == 429 || body.contains("Rate limit exceeded")) {
                            String retryAfter = resp.header("Retry-After");
                            long waitMs = 20_000;
                            if (retryAfter != null) {
                                try { waitMs = Math.min(Long.parseLong(retryAfter.trim()) * 1000, 20_000); }
                                catch (NumberFormatException ignored) {}
                            }
                            final long finalWaitMs = waitMs;
                            LogUtility.w("Legacy queue: rate limited, pausing " + waitMs + "ms then retrying");
                            runOnUiThread(() -> Toast.makeText(FavoriteActivity.this,
                                getString(R.string.rate_limit_wait) + " (" + (finalWaitMs / 1000) + "s)", Toast.LENGTH_LONG).show());
                            rateLimited = true;
                            Thread.sleep(waitMs);
                            j--; // retry same gallery
                        } else {
                            LogUtility.w("Legacy queue: gallery " + galleryId + " fetch failed with HTTP " + code + ", skipping");
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LogUtility.e("Legacy queue: failed for gallery " + galleryId + ": " + e.getMessage(), e);
                } finally {
                    if (!rateLimited) GalleryData.queuedForRefresh.remove(galleryId);
                }
                if (!rateLimited) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }
            legacyQueueRunning = false;
            LogUtility.i("Legacy refresh queue complete");
        }).start();
    }

    @Override
    protected void onDestroy() {
        legacyQueueRunning = false;
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.download_page).setVisible(true);
        menu.findItem(R.id.sort_by_name).setVisible(true);
        menu.findItem(R.id.by_popular).setVisible(false);
        menu.findItem(R.id.only_language).setVisible(false);
        menu.findItem(R.id.add_bookmark).setVisible(false);

        searchView = (androidx.appcompat.widget.SearchView) menu.findItem(R.id.search).getActionView();
        Objects.requireNonNull(searchView).setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                pageSwitcher.setTotalPage(calculatePages(newText));
                if (adapter != null)
                    adapter.getFilter().filter(newText);
                return true;
            }
        });
        Utility.tintMenu(this, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;
        if (item.getItemId() == R.id.open_browser) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(Utility.getBaseUrl() + "favorites/"));
            startActivity(i);
        } else if (item.getItemId() == R.id.download_page) {
            if (adapter != null) showDialogDownloadAll();
        } else if (item.getItemId() == R.id.sort_by_name) {
            sortByTitle = !sortByTitle;
            adapter.setSortByTitle(sortByTitle);
            item.setTitle(sortByTitle ? R.string.sort_by_latest : R.string.sort_by_title);
        } else if (item.getItemId() == R.id.random_favorite) {
            startActivity(new Intent(this, RandomFavoriteActivity.class));
        }
        return super.onOptionsItemSelected(item);
    }

    private void showDialogDownloadAll() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder
            .setTitle(R.string.download_all_galleries_in_this_page)
            .setIcon(R.drawable.ic_file)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                for (Gallery g : adapter.getAllGalleries())
                    DownloadGalleryV2.downloadGallery(this, g);
            });
        builder.show();
    }
}
