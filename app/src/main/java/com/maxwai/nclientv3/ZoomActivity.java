package com.maxwai.nclientv3;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.maxwai.nclientv3.components.widgets.CustomLinearLayoutManager;

import com.bumptech.glide.Priority;
import com.maxwai.nclientv3.adapters.WebtoonAdapter;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.views.ZoomFragment;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.DefaultDialogs;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Objects;

public class ZoomActivity extends GeneralActivity {
    private static final int HIDE_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    private static final int SHOW_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
    private static final String VOLUME_SIDE_KEY = "volumeSide";
    private static final String SCROLL_TYPE_KEY = "zoomScrollType";
    private static final int PREFETCH_DELAY_MS = 150;
    private static final boolean DEBUG = false;
    
    private GenericGallery gallery;
    private int actualPage = 0;
    private boolean isHidden = false;
    private ViewPager2 mViewPager;
    private RecyclerView mWebtoonRecyclerView;
    private com.maxwai.nclientv3.adapters.WebtoonAdapter mWebtoonAdapter;
    private TextView pageManagerLabel, cornerPageViewer;
    private View pageSwitcher;
    private SeekBar seekBar;
    private Toolbar toolbar;
    private View view;
    private GalleryFolder directory;
    @ViewPager2.Orientation
    private int tmpScrollType;
    private boolean up = false, down = false, side;
    private RecyclerView.OnScrollListener webtoonScrollListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        SharedPreferences preferences = getSharedPreferences("Settings", 0);
        side = preferences.getBoolean(VOLUME_SIDE_KEY, true);
        setContentView(R.layout.activity_zoom);

        //read arguments
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gallery = getIntent().getParcelableExtra(getPackageName() + ".GALLERY", GenericGallery.class);
        } else {
            gallery = getIntent().getParcelableExtra(getPackageName() + ".GALLERY");
        }
        final int page = Objects.requireNonNull(getIntent().getExtras()).getInt(getPackageName() + ".PAGE", 1) - 1;
        directory = gallery.getGalleryFolder();
        //toolbar setup
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        setTitle(gallery.getTitle());

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Global.isLockScreen())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            SectionsPagerAdapter mSectionsPagerAdapter = new SectionsPagerAdapter(this);
            mViewPager = findViewById(R.id.container);
            
            if (mViewPager == null) {
                throw new IllegalStateException("ViewPager not found in layout");
            }
            
            mViewPager.setAdapter(mSectionsPagerAdapter);
            int savedScrollType = preferences.getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
            
            if (savedScrollType == ScrollType.HORIZONTAL.ordinal()) {
                mViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            } else if (savedScrollType == ScrollType.VERTICAL.ordinal()) {
                mViewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
            } else {
                mViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            }
            
            mViewPager.setOffscreenPageLimit(Global.getOffscreenLimit());
            
            mWebtoonRecyclerView = findViewById(R.id.webtoon_container);
            
            if (mWebtoonRecyclerView == null) {
                throw new IllegalStateException("Webtoon RecyclerView not found in layout");
            }
            
            mWebtoonAdapter = new WebtoonAdapter(this, gallery, directory);
            mWebtoonRecyclerView.setAdapter(mWebtoonAdapter);
            
            CustomLinearLayoutManager layoutManager = new CustomLinearLayoutManager(this);
            mWebtoonRecyclerView.setLayoutManager(layoutManager);
            layoutManager.setItemPrefetchEnabled(true);
            layoutManager.setInitialPrefetchItemCount(4);
            mWebtoonRecyclerView.setItemViewCacheSize(10);
            
            mWebtoonAdapter.setClickListener(v -> {
                isHidden = !isHidden;
                applyVisibilityFlag();
                animateLayout();
            });

            if (Global.useRtl()) {
                mWebtoonRecyclerView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            }
            
            if (savedScrollType == ScrollType.WEBTOON.ordinal()) {
                switchToScrollType(ScrollType.WEBTOON.ordinal());
            }
        } catch (Exception e) {
            LogUtility.e("Error setting up reader: " + e.getMessage(), e);
            Toast.makeText(this, R.string.unable_to_connect_to_the_site, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        pageSwitcher = findViewById(R.id.page_switcher);
        pageManagerLabel = findViewById(R.id.pages);
        cornerPageViewer = findViewById(R.id.page_text);
        seekBar = findViewById(R.id.seekBar);
        view = findViewById(R.id.view);

        //initial setup for views
        changeLayout(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        mViewPager.setKeepScreenOn(Global.isLockScreen());
        findViewById(R.id.prev).setOnClickListener(v -> changeClosePage(false));
        findViewById(R.id.next).setOnClickListener(v -> changeClosePage(true));
        seekBar.setMax(gallery.getPageCount() - 1);
        if (Global.useRtl()) {
            seekBar.setRotationY(180);
            mViewPager.setLayoutDirection(ViewPager2.LAYOUT_DIRECTION_RTL);
        }

        //Adding listeners
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int newPage) {
                int oldPage = actualPage;
                actualPage = newPage;
                LogUtility.d("Page selected: " + newPage + " from page " + oldPage);
                setPageText(newPage + 1);
                seekBar.setProgress(newPage);
                clearFarRequests(oldPage, newPage);
                makeNearRequests(newPage);
            }
        });

        // No layout-change-based preloading: the scroll listener already handles this.
        pageManagerLabel.setOnClickListener(v -> DefaultDialogs.pageChangerDialog(
            new DefaultDialogs.Builder(this)
                .setActual(actualPage + 1)
                .setMin(1)
                .setMax(gallery.getPageCount())
                .setTitle(R.string.change_page)
                .setDrawable(R.drawable.ic_find_in_page)
                .setDialogs(new DefaultDialogs.CustomDialogResults() {
                    @Override
                    public void positive(int actual) {
                        changePage(actual - 1);
                    }
                })
        ));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    setPageText(progress + 1);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int scrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
                if (scrollType == ScrollType.WEBTOON.ordinal()) {
                    final int targetPage = Math.max(0, Math.min(seekBar.getProgress(), gallery.getPageCount() - 1));
                    mWebtoonRecyclerView.scrollToPosition(targetPage);
                    mWebtoonRecyclerView.postDelayed(() -> makeNearRequestsWebtoon(targetPage), PREFETCH_DELAY_MS);
                } else {
                    changePage(seekBar.getProgress());
                }
            }
        });


        changePage(page);
        setPageText(page + 1);
        seekBar.setProgress(page);
    }

    private void setUserInput(boolean enabled) {
        mViewPager.setUserInputEnabled(enabled);
    }

    private void setPageText(int page) {
        pageManagerLabel.setText(getString(R.string.page_format, page, gallery.getPageCount()));
        cornerPageViewer.setText(getString(R.string.page_format, page, gallery.getPageCount()));
    }


    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Global.volumeOverride()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    up = false;
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    down = false;
                    return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Global.volumeOverride()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    up = true;
                    changeClosePage(side);
                    if (up && down) changeSide();
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    down = true;
                    changeClosePage(!side);
                    if (up && down) changeSide();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void changeSide() {
        getSharedPreferences("Settings", 0).edit().putBoolean(VOLUME_SIDE_KEY, side = !side).apply();
        Toast.makeText(this, side ? R.string.next_page_volume_up : R.string.next_page_volume_down, Toast.LENGTH_SHORT).show();
    }

    public void changeClosePage(boolean next) {
        if (Global.useRtl()) next = !next;
        
        int currentScrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
        
        if (currentScrollType == ScrollType.WEBTOON.ordinal()) {
            CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) mWebtoonRecyclerView.getLayoutManager();
            if (layoutManager != null) {
                int currentPosition = layoutManager.findFirstCompletelyVisibleItemPosition();
                if (currentPosition == RecyclerView.NO_POSITION) {
                    currentPosition = layoutManager.findFirstVisibleItemPosition();
                }
                
                if (currentPosition == RecyclerView.NO_POSITION) {
                    currentPosition = 0;
                }
                
                int targetPosition = currentPosition + (next ? 1 : -1);
                
                targetPosition = Math.max(0, Math.min(targetPosition, gallery.getPageCount() - 1));
                
                if (targetPosition != currentPosition) {
                    mWebtoonRecyclerView.smoothScrollToPosition(targetPosition);
                    final int finalTargetPosition = targetPosition;
                    mWebtoonRecyclerView.postDelayed(() -> makeNearRequestsWebtoon(finalTargetPosition), PREFETCH_DELAY_MS);
                }
            }
        } else {
            if (next && mViewPager.getCurrentItem() < (Objects.requireNonNull(mViewPager.getAdapter()).getItemCount() - 1))
                changePage(mViewPager.getCurrentItem() + 1);
            if (!next && mViewPager.getCurrentItem() > 0) changePage(mViewPager.getCurrentItem() - 1);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        changeLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    private boolean hardwareKeys() {
        return ViewConfiguration.get(this).hasPermanentMenuKey();
    }

    private void applyMargin(boolean landscape, View view) {
        ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) view.getLayoutParams();
        lp.setMargins(0, 0, landscape && !hardwareKeys() ? Global.getNavigationBarHeight(this) : 0, 0);
        view.setLayoutParams(lp);
    }

    private void changeLayout(boolean landscape) {
        int statusBarHeight = Global.getStatusBarHeight(this);
        applyMargin(landscape, findViewById(R.id.master_layout));
        applyMargin(landscape, toolbar);
        pageSwitcher.setPadding(0, 0, 0, landscape ? 0 : statusBarHeight);
    }

    private void changePage(int newPage) {
        mViewPager.setCurrentItem(newPage);
    }

    private void changeScrollTypeDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        int scrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
        tmpScrollType = scrollType;
        builder.setTitle(getString(R.string.change_scroll_type) + ":");
        builder.setSingleChoiceItems(R.array.scroll_type, scrollType, (dialog, which) -> {
            tmpScrollType = which;
        });
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            if (tmpScrollType != scrollType) {
                switchToScrollType(tmpScrollType);
                int page = Math.max(0, Math.min(actualPage, gallery.getPageCount() - 1));
                if (tmpScrollType == ScrollType.WEBTOON.ordinal()) {
                    if (mWebtoonRecyclerView != null) {
                        mWebtoonRecyclerView.scrollToPosition(page);
                        mWebtoonRecyclerView.postDelayed(() -> makeNearRequestsWebtoon(page), PREFETCH_DELAY_MS);
                    }
                } else {
                    changePage(page);
                }
            }
        }).setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    private void switchToScrollType(int scrollType) {
        if (mViewPager == null || mWebtoonRecyclerView == null || mWebtoonAdapter == null) {
            LogUtility.e("Critical view is null in switchToScrollType");
            return;
        }
        
        try {
            if (scrollType == ScrollType.WEBTOON.ordinal()) {
                mViewPager.setVisibility(View.GONE);
                mWebtoonRecyclerView.setVisibility(View.VISIBLE);
                
                if (webtoonScrollListener != null) {
                    mWebtoonRecyclerView.removeOnScrollListener(webtoonScrollListener);
                }
                
                webtoonScrollListener = new RecyclerView.OnScrollListener() {
                    private int lastVisiblePosition = -1;

                    @Override
                    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                        super.onScrolled(recyclerView, dx, dy);
                        try {
                            CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) recyclerView.getLayoutManager();
                            if (layoutManager != null) {
                                int firstVisiblePosition = layoutManager.findFirstVisibleItemPosition();
                                if (firstVisiblePosition != RecyclerView.NO_POSITION) {
                                    setPageText(firstVisiblePosition + 1);
                                    seekBar.setProgress(firstVisiblePosition);
                                    // Keep actualPage in sync so bookmark/share use the correct page
                                    actualPage = firstVisiblePosition;

                                    if (lastVisiblePosition == -1 || Math.abs(firstVisiblePosition - lastVisiblePosition) >= 1) {
                                        makeNearRequestsWebtoon(firstVisiblePosition);
                                        lastVisiblePosition = firstVisiblePosition;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LogUtility.e("Error in scroll listener: " + e.getMessage(), e);
                        }
                    }
                };
                
                mWebtoonRecyclerView.addOnScrollListener(webtoonScrollListener);
                
                mWebtoonRecyclerView.post(() -> {
                    CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) mWebtoonRecyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int firstVisible = layoutManager.findFirstVisibleItemPosition();
                        if (firstVisible != RecyclerView.NO_POSITION && firstVisible >= 0 && firstVisible < gallery.getPageCount()) {
                            makeNearRequestsWebtoon(firstVisible);
                        }
                    }
                });
            } else {
                mWebtoonRecyclerView.setVisibility(View.GONE);
                mViewPager.setVisibility(View.VISIBLE);
                int orientation = (scrollType == ScrollType.VERTICAL.ordinal())
                    ? ViewPager2.ORIENTATION_VERTICAL
                    : ViewPager2.ORIENTATION_HORIZONTAL;
                mViewPager.setOrientation(orientation);
            }
            
            getSharedPreferences("Settings", 0).edit().putInt(SCROLL_TYPE_KEY, scrollType).apply();
        } catch (Exception e) {
            LogUtility.e("Error in switchToScrollType: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_zoom, menu);
        Utility.tintMenu(this, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.rotate) {
            int scrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
            if (scrollType == ScrollType.WEBTOON.ordinal()) {
                CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) mWebtoonRecyclerView.getLayoutManager();
                if (layoutManager != null) {
                    int position = layoutManager.findFirstVisibleItemPosition();
                    if (position != RecyclerView.NO_POSITION && position >= 0 && position < gallery.getPageCount()) {
                        mWebtoonAdapter.rotatePage(position);
                    }
                }
            } else {
                ZoomFragment fragment = getActualFragment();
                if (fragment != null) {
                    fragment.rotate();
                }
            }
        } else if (id == R.id.save_page) {
            if (Global.hasStoragePermission(this)) {
                downloadPage();
            } else requestStorage();
        } else if (id == R.id.share) {
            if (gallery.getId() <= 0) sendImage(false);
            else openSendImageDialog();
        } else if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.bookmark) {
            Queries.ResumeTable.insert(gallery.getId(), actualPage + 1);
        } else if (id == R.id.scrollType) {
            changeScrollTypeDialog();
        }

        return super.onOptionsItemSelected(item);
    }

    private void openSendImageDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> sendImage(true))
            .setNegativeButton(R.string.no, (dialog, which) -> sendImage(false))
            .setCancelable(true).setTitle(R.string.send_with_title)
            .setMessage(R.string.caption_send_with_title)
            .show();
    }

    private void requestStorage() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Global.initStorage(this);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            downloadPage();

    }

    private ZoomFragment getActualFragment() {
        return getActualFragment(mViewPager.getCurrentItem());
    }

    private void makeNearRequests(int newPage) {
        ZoomFragment fragment;
        int offScreenLimit = Global.getOffscreenLimit();
        for (int i = newPage - offScreenLimit; i <= newPage + offScreenLimit; i++) {
            fragment = getActualFragment(i);
            if (fragment == null) continue;
            if (i == newPage) fragment.loadImage(Priority.IMMEDIATE);
            else fragment.loadImage();
        }
    }

    private void makeNearRequestsWebtoon(int currentPage) {
        if (currentPage < 0 || currentPage >= gallery.getPageCount()) {
            return;
        }

        int offScreenLimit = Global.getOffscreenLimit();

        for (int i = currentPage - offScreenLimit; i <= currentPage + offScreenLimit; i++) {
            if (i >= 0 && i < gallery.getPageCount() && i != currentPage) {
                // preloadPage does its own findViewHolderForAdapterPosition internally
                mWebtoonAdapter.preloadPage(mWebtoonRecyclerView, i);
            }
        }
    }

    private void clearFarRequests(int oldPage, int newPage) {
        ZoomFragment fragment;
        int offScreenLimit = Global.getOffscreenLimit();
        for (int i = oldPage - offScreenLimit; i <= oldPage + offScreenLimit; i++) {
            if (i >= newPage - offScreenLimit && i <= newPage + offScreenLimit) continue;
            fragment = getActualFragment(i);
            if (fragment == null) continue;
            fragment.cancelRequest();
        }

    }

    private ZoomFragment getActualFragment(int position) {
        return (ZoomFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    private Drawable getCurrentImageDrawable() {
        int scrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
        if (scrollType == ScrollType.WEBTOON.ordinal()) {
            CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) mWebtoonRecyclerView.getLayoutManager();
            if (layoutManager != null) {
                int position = layoutManager.findFirstVisibleItemPosition();
                if (position != RecyclerView.NO_POSITION && position >= 0 && position < gallery.getPageCount()) {
                    Drawable drawable = mWebtoonAdapter.getDrawableAtPosition(mWebtoonRecyclerView, position);
                    if (drawable != null) {
                        return drawable;
                    }
                }
            }
        } else {
            ZoomFragment fragment = getActualFragment();
            if (fragment != null) {
                return fragment.getDrawable();
            }
        }
        return null;
    }

    private int getCurrentPage() {
        int scrollType = getSharedPreferences("Settings", 0).getInt(SCROLL_TYPE_KEY, ScrollType.HORIZONTAL.ordinal());
        if (scrollType == ScrollType.WEBTOON.ordinal()) {
            CustomLinearLayoutManager layoutManager = (CustomLinearLayoutManager) mWebtoonRecyclerView.getLayoutManager();
            if (layoutManager != null) {
                int position = layoutManager.findFirstVisibleItemPosition();
                return Math.max(0, Math.min(position, gallery.getPageCount() - 1));
            }
        } else {
            return mViewPager.getCurrentItem();
        }
        return 0;
    }

    private void sendImage(boolean withText) {
        int pageNum = getCurrentPage();
        Drawable drawable = getCurrentImageDrawable();
        Utility.sendImage(this, drawable, withText ? gallery.sharePageUrl(pageNum) : null);
    }

    private void downloadPage() {
        final File output = new File(Global.SCREENFOLDER, gallery.getId() + "-" + (getCurrentPage() + 1) + ".jpg");
        Utility.saveImage(getCurrentImageDrawable(), output);
    }

    private void animateLayout() {
        AnimatorListenerAdapter adapter = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (isHidden) {
                    pageSwitcher.setVisibility(View.GONE);
                    toolbar.setVisibility(View.GONE);
                    if (view != null) {
                        view.setVisibility(View.GONE);
                    }
                    cornerPageViewer.setVisibility(View.VISIBLE);
                }
            }
        };

        pageSwitcher.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        if (view != null) {
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
        }
        cornerPageViewer.setVisibility(View.GONE);

        pageSwitcher.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
        toolbar.animate().alpha(isHidden ? 0f : 0.75f).setDuration(150).setListener(adapter).start();
    }

    private void applyVisibilityFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (isHidden) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(isHidden ? HIDE_FLAGS : SHOW_FLAGS);
        }
    }

    private enum ScrollType {HORIZONTAL, VERTICAL, WEBTOON}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWebtoonRecyclerView != null && webtoonScrollListener != null) {
            mWebtoonRecyclerView.removeOnScrollListener(webtoonScrollListener);
            webtoonScrollListener = null;
        }
        if (mWebtoonAdapter != null) {
            mWebtoonAdapter.setClickListener(null);
        }
    }

    public class SectionsPagerAdapter extends FragmentStateAdapter {
        public SectionsPagerAdapter(ZoomActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());

        }

        private boolean allowScroll = true;

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            ZoomFragment f = ZoomFragment.newInstance(gallery, position, directory);

            f.setZoomChangeListener((v, zoomLevel) -> {
                try {
                    boolean _allowScroll = zoomLevel < 1.1f;
                    if (_allowScroll != allowScroll) {
                        setUserInput(!allowScroll);
                        allowScroll = _allowScroll;
                    }
                } catch (Exception ignored) {
                }
            });

            f.setClickListener(v -> {
                isHidden = !isHidden;
                LogUtility.d("Clicked " + isHidden);
                applyVisibilityFlag();
                animateLayout();
            });
            return f;
        }

        @Override
        public int getItemCount() {
            return gallery.getPageCount();
        }
    }
}
