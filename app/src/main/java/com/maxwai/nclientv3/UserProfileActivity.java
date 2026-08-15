package com.maxwai.nclientv3;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.maxwai.nclientv3.adapters.RecentCommentAdapter;
import com.maxwai.nclientv3.adapters.RecentFavoriteAdapter;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.api.users.UserProfile;
import com.maxwai.nclientv3.api.users.UserProfileFetcher;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.ImageDownloadUtility;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Shows another user's public profile, reached by tapping their name or avatar on a comment.
 */
public class UserProfileActivity extends GeneralActivity {
    public static final String EXTRA_USER_ID = ".USERID";
    public static final String EXTRA_SLUG = ".SLUG";
    public static final String EXTRA_USERNAME = ".USERNAME";

    private ImageView avatar;
    private TextView username, joined, staffBadge, aboutTitle, about;
    private TextView favoriteTagsTitle, favoritesTitle, commentsTitle;
    private ChipGroup favoriteTags;
    private RecyclerView recentFavorites, recentComments;
    private ProgressBar progress;
    private TextView error;

    /**
     * Opens the profile of a comment's poster. Both the id and the slug are required by the
     * API, and both come straight off the comment's poster object.
     */
    public static void start(@NonNull android.content.Context context, int userId, String slug, String username) {
        if (userId <= 0 || slug == null || slug.isEmpty()) return;
        Intent intent = new Intent(context, UserProfileActivity.class);
        intent.putExtra(context.getPackageName() + EXTRA_USER_ID, userId);
        intent.putExtra(context.getPackageName() + EXTRA_SLUG, slug);
        intent.putExtra(context.getPackageName() + EXTRA_USERNAME, username);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_profile);

        int userId = getIntent().getIntExtra(getPackageName() + EXTRA_USER_ID, -1);
        String slug = getIntent().getStringExtra(getPackageName() + EXTRA_SLUG);
        String initialName = getIntent().getStringExtra(getPackageName() + EXTRA_USERNAME);
        if (userId <= 0 || slug == null) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(R.string.user_profile);

        findViews();
        // Show what the comment already told us so the screen is not blank while loading.
        if (initialName != null) username.setText(initialName);

        recentFavorites.setLayoutManager(
            new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        recentComments.setLayoutManager(new LinearLayoutManager(this));

        new UserProfileFetcher(userId, slug, new UserProfileFetcher.Response() {
            @Override
            public void onSuccess(@NonNull UserProfile profile) {
                runOnUiThread(() -> bind(profile));
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    error.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void findViews() {
        avatar = findViewById(R.id.avatar);
        username = findViewById(R.id.username);
        joined = findViewById(R.id.joined);
        staffBadge = findViewById(R.id.staff_badge);
        aboutTitle = findViewById(R.id.about_title);
        about = findViewById(R.id.about);
        favoriteTagsTitle = findViewById(R.id.favorite_tags_title);
        favoriteTags = findViewById(R.id.favorite_tags);
        favoritesTitle = findViewById(R.id.recent_favorites_title);
        recentFavorites = findViewById(R.id.recent_favorites);
        commentsTitle = findViewById(R.id.recent_comments_title);
        recentComments = findViewById(R.id.recent_comments);
        progress = findViewById(R.id.progress);
        error = findViewById(R.id.error);
    }

    private void bind(@NonNull UserProfile profile) {
        progress.setVisibility(View.GONE);
        error.setVisibility(View.GONE);

        username.setText(profile.getUsername());
        staffBadge.setVisibility(profile.isStaff() || profile.isSuperuser() ? View.VISIBLE : View.GONE);
        bindJoinDate(profile.getDateJoined());

        Uri avatarUrl = profile.getAvatarUrl();
        if (avatarUrl == null || Global.getDownloadPolicy() == Global.DataUsageType.NONE)
            ImageDownloadUtility.loadImage(R.drawable.ic_person, avatar);
        else
            ImageDownloadUtility.loadImage(this, avatarUrl, avatar);

        bindAbout(profile.getAbout());
        bindFavoriteTags(profile.getFavoriteTags());
        bindRecentFavorites(profile.getRecentFavorites());
        bindRecentComments(profile.getRecentComments());
    }

    /**
     * Mirrors the website: a relative age plus the absolute date, e.g. "1 month ago (7/10/2026)".
     */
    private void bindJoinDate(Date dateJoined) {
        if (dateJoined == null) {
            joined.setVisibility(View.GONE);
            return;
        }
        CharSequence relative = DateUtils.getRelativeTimeSpanString(
            dateJoined.getTime(), System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS);
        String absolute = android.text.format.DateFormat.getDateFormat(this).format(dateJoined);
        joined.setText(getString(R.string.joined_format, relative, absolute));
        joined.setVisibility(View.VISIBLE);
    }

    private void bindAbout(String text) {
        boolean has = text != null;
        aboutTitle.setVisibility(has ? View.VISIBLE : View.GONE);
        about.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) about.setText(text);
    }

    private void bindFavoriteTags(List<String> tags) {
        favoriteTags.removeAllViews();
        boolean has = !tags.isEmpty();
        favoriteTagsTitle.setVisibility(has ? View.VISIBLE : View.GONE);
        favoriteTags.setVisibility(has ? View.VISIBLE : View.GONE);
        for (String tag : tags) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.chip_layout, favoriteTags, false);
            chip.setText(tag);
            chip.setClickable(false);
            favoriteTags.addView(chip);
        }
    }

    private void bindRecentFavorites(List<UserProfile.RecentFavorite> favorites) {
        boolean has = !favorites.isEmpty();
        favoritesTitle.setVisibility(View.VISIBLE);
        recentFavorites.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) {
            favoritesTitle.setText(R.string.no_recent_favorites);
            return;
        }
        recentFavorites.setAdapter(new RecentFavoriteAdapter(this, favorites, this::openGallery));
    }

    private void bindRecentComments(List<UserProfile.RecentComment> comments) {
        boolean has = !comments.isEmpty();
        commentsTitle.setVisibility(View.VISIBLE);
        recentComments.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) {
            commentsTitle.setText(R.string.no_recent_comments);
            return;
        }
        recentComments.setAdapter(new RecentCommentAdapter(this, comments, this::openGallery));
    }

    /**
     * Only the gallery id travels with a favourite or a comment, so resolve it to a full
     * gallery before handing it to GalleryActivity.
     */
    private void openGallery(int galleryId) {
        InspectorV3.galleryInspector(this, galleryId, new InspectorV3.DefaultInspectorResponse() {
            @Override
            public void onSuccess(List<GenericGallery> galleries) {
                if (galleries.isEmpty()) {
                    onFailure(null);
                    return;
                }
                Intent intent = new Intent(UserProfileActivity.this, GalleryActivity.class);
                intent.putExtra(getPackageName() + ".GALLERY", galleries.get(0));
                runOnUiThread(() -> startActivity(intent));
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> Toast.makeText(UserProfileActivity.this,
                    R.string.unable_to_connect_to_the_site, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
