package com.maxwai.nclientv3;

import android.content.Intent;
import android.content.res.Configuration;
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
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.async.downloader.DownloadGalleryV2;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.views.PageSwitcher;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.FavoriteSyncManager;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Objects;


public class FavoriteActivity extends BaseActivity {
    private static final int ENTRY_PER_PAGE = 24;
    private FavoriteAdapter adapter = null;
    private boolean sortByTitle = false;
    private PageSwitcher pageSwitcher;
    private SearchView searchView;
    private MenuItem syncItem;
    private boolean syncRunning = false;

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
        actionBar.setTitle(R.string.favorites_title);
        pageSwitcher = findViewById(R.id.page_switcher);
        recycler = findViewById(R.id.recycler);
        refresher = findViewById(R.id.refresher);
        refresher.setRefreshing(true);
        adapter = new FavoriteAdapter(this);


        refresher.setOnRefreshListener(adapter::forceReload);
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        recycler.setAdapter(adapter);
        pageSwitcher.setPages(1, 1);
        pageSwitcher.setChanger(new PageSwitcher.DefaultPageChanger() {
            @Override
            public void pageChanged() {
                if (adapter != null) {
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.download_page).setVisible(true);
        menu.findItem(R.id.sort_by_name).setVisible(true);
        menu.findItem(R.id.by_popular).setVisible(false);
        menu.findItem(R.id.only_language).setVisible(false);
        menu.findItem(R.id.add_bookmark).setVisible(false);
        menu.findItem(R.id.open_browser).setVisible(false);
        MenuItem searchItem = menu.findItem(R.id.search);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS
            | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        syncItem = menu.findItem(R.id.sync_favorites);
        syncItem.setVisible(true);
        syncItem.setEnabled(!syncRunning);
        MenuItem randomItem = menu.findItem(R.id.random_favorite);
        randomItem.setVisible(true);
        randomItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        searchView = (androidx.appcompat.widget.SearchView) searchItem.getActionView();
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
        if (item.getItemId() == R.id.sync_favorites) {
            confirmFavoriteSync();
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

    private void confirmFavoriteSync() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_sync_web)
            .setMessage(R.string.favorite_sync_web_message)
            .setPositiveButton(R.string.favorite_sync_confirm,
                (dialog, which) -> syncFavorites())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void syncFavorites() {
        if (syncRunning) return;
        if (!Login.canAccessAuthenticatedApi(this)) {
            Toast.makeText(this, R.string.favorite_sync_login_required, Toast.LENGTH_LONG).show();
            return;
        }

        syncRunning = true;
        if (syncItem != null) syncItem.setEnabled(false);
        refresher.setRefreshing(true);
        FavoriteSyncManager.sync(this, new FavoriteSyncManager.Listener() {
            @Override
            public void onProgress(boolean uploading, int completed, int total) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ActionBar actionBar = getSupportActionBar();
                    if (actionBar != null) actionBar.setSubtitle(getString(uploading
                        ? R.string.favorite_sync_upload_progress
                        : R.string.favorite_sync_download_progress, completed, total));
                });
            }

            @Override
            public void onComplete(FavoriteSyncManager.Result result) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) finishSync(result);
                });
            }

            @Override
            public void onFailure() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    resetSyncUi();
                    Toast.makeText(FavoriteActivity.this,
                        R.string.favorite_sync_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void finishSync(FavoriteSyncManager.Result result) {
        resetSyncUi();
        String query = searchView == null ? null : searchView.getQuery().toString();
        pageSwitcher.setTotalPage(calculatePages(query));
        if (adapter != null) adapter.forceReload();
        int message = result.failed == 0
            ? R.string.favorite_sync_complete : R.string.favorite_sync_partial;
        Toast.makeText(this, getString(message,
            result.downloaded, result.uploaded, result.failed), Toast.LENGTH_LONG).show();
    }

    private void resetSyncUi() {
        syncRunning = false;
        if (syncItem != null) syncItem.setEnabled(true);
        refresher.setRefreshing(false);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setSubtitle(null);
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
