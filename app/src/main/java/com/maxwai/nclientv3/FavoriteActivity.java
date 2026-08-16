package com.maxwai.nclientv3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

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

import java.util.ArrayList;
import java.util.List;
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
        syncItem.setEnabled(!syncRunning && !FavoriteSyncManager.isRunning());
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
        // A sync started before this Activity existed may still be running. Show what it is
        // doing rather than leaving the tap looking like it did nothing.
        if (FavoriteSyncManager.isRunning()) {
            showSyncProgressDialog();
            return;
        }
        requestNotificationPermission();

        syncRunning = true;
        if (syncItem != null) syncItem.setEnabled(false);
        refresher.setRefreshing(true);
        boolean started = FavoriteSyncManager.sync(this, new FavoriteSyncManager.Listener() {
            @Override
            public void onProgress(boolean writing, int completed, int total) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ActionBar actionBar = getSupportActionBar();
                    if (actionBar != null) actionBar.setSubtitle(FavoriteSyncManager
                        .phaseText(FavoriteActivity.this, writing, completed, total));
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
        // Lost the race with a sync started elsewhere; hand the UI back.
        if (!started) resetSyncUi();
    }

    private void finishSync(FavoriteSyncManager.Result result) {
        resetSyncUi();
        String query = searchView == null ? null : searchView.getQuery().toString();
        pageSwitcher.setTotalPage(calculatePages(query));
        if (adapter != null) adapter.forceReload();
        Toast.makeText(this, FavoriteSyncManager.describe(this, result), Toast.LENGTH_SHORT).show();
    }

    /**
     * Live view of a sync that is already in flight. Polls the manager's snapshot rather than
     * subscribing, so there is no listener to unregister if the dialog or Activity goes away
     * mid-sync, and a dialog opened at any point still shows the current phase.
     */
    private void showSyncProgressDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_sync_progress, null);
        TextView phase = content.findViewById(R.id.phase);
        ProgressBar bar = content.findViewById(R.id.progress);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_sync_notification_title)
            .setView(content)
            .setPositiveButton(R.string.hide, null)
            .show();

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                if (!FavoriteSyncManager.isRunning()) {
                    phase.setText(R.string.favorite_sync_finished);
                    bar.setIndeterminate(false);
                    bar.setProgress(bar.getMax());
                    return;
                }
                FavoriteSyncManager.Progress p = FavoriteSyncManager.getProgress();
                phase.setText(FavoriteSyncManager.describeProgress(FavoriteActivity.this));
                if (p.total > 0) {
                    bar.setIndeterminate(false);
                    bar.setMax(p.total);
                    bar.setProgress(p.completed);
                } else {
                    bar.setIndeterminate(true);
                }
                handler.postDelayed(this, 500);
            }
        };
        dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
        tick.run();
    }

    /**
     * The manifest declares POST_NOTIFICATIONS but nothing ever asked for it, so on Android 13+
     * every notification the app posts was being dropped silently by NotificationSettings.
     * Asked here rather than at startup so the prompt arrives with an obvious reason attached.
     * The sync runs either way; only its progress notification depends on the answer.
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
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
