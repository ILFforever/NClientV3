package com.maxwai.nclientv3.api.users;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.Nullable;

import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A public user profile as returned by {@code GET /api/v2/users/{user_id}/{slug}}.
 */
public class UserProfile {
    private final List<RecentFavorite> recentFavorites = new ArrayList<>();
    private final List<RecentComment> recentComments = new ArrayList<>();
    private int id;
    private String username, slug, avatarUrl, about, favoriteTags;
    private Date dateJoined;
    private boolean isStaff, isSuperuser;

    public UserProfile(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            // Several fields are declared nullable; skip them rather than crash on a null token.
            if (reader.peek() == JsonToken.NULL) {
                reader.nextName();
                reader.nextNull();
                continue;
            }
            switch (reader.nextName()) {
                case "id":
                    id = reader.nextInt();
                    break;
                case "username":
                    username = nextStringOrNull(reader);
                    break;
                case "slug":
                    slug = nextStringOrNull(reader);
                    break;
                case "avatar_url":
                    avatarUrl = nextStringOrNull(reader);
                    break;
                case "about":
                    about = nextStringOrNull(reader);
                    break;
                case "favorite_tags":
                    favoriteTags = nextStringOrNull(reader);
                    break;
                case "date_joined":
                    dateJoined = new Date(reader.nextLong() * 1000);
                    break;
                case "is_staff":
                    isStaff = reader.nextBoolean();
                    break;
                case "is_superuser":
                    isSuperuser = reader.nextBoolean();
                    break;
                case "recent_favorites":
                    reader.beginArray();
                    while (reader.hasNext()) recentFavorites.add(new RecentFavorite(reader));
                    reader.endArray();
                    break;
                case "recent_comments":
                    reader.beginArray();
                    while (reader.hasNext()) recentComments.add(new RecentComment(reader));
                    reader.endArray();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
    }

    @Nullable
    private static String nextStringOrNull(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getSlug() {
        return slug;
    }

    @Nullable
    public Uri getAvatarUrl() {
        if (avatarUrl == null || avatarUrl.isEmpty()) return null;
        if (avatarUrl.startsWith("http")) return Uri.parse(avatarUrl);
        return Uri.parse(String.format(Locale.US, "https://%s/%s", Utility.getImageHost(), avatarUrl));
    }

    @Nullable
    public String getAbout() {
        return about == null || about.trim().isEmpty() ? null : about.trim();
    }

    /**
     * The API returns favourite tags as one free-form string; split it so the UI can chip them.
     */
    public List<String> getFavoriteTags() {
        if (favoriteTags == null || favoriteTags.trim().isEmpty()) return new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (String tag : Arrays.asList(favoriteTags.split("[,\\n]"))) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) tags.add(trimmed);
        }
        return tags;
    }

    @Nullable
    public Date getDateJoined() {
        return dateJoined;
    }

    public boolean isStaff() {
        return isStaff;
    }

    public boolean isSuperuser() {
        return isSuperuser;
    }

    public List<RecentFavorite> getRecentFavorites() {
        return recentFavorites;
    }

    public List<RecentComment> getRecentComments() {
        return recentComments;
    }

    /**
     * A gallery preview from the profile's favourites. This is a flattened shape, not the
     * regular gallery object, so it cannot be parsed with {@code GalleryData}.
     */
    public static class RecentFavorite {
        private int id, numPages;
        private String englishTitle, japaneseTitle, mediaId, thumbnail;

        RecentFavorite(JsonReader reader) throws IOException {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "id":
                        id = reader.nextInt();
                        break;
                    case "num_pages":
                        numPages = reader.nextInt();
                        break;
                    case "english_title":
                        englishTitle = nextStringOrNull(reader);
                        break;
                    case "japanese_title":
                        japaneseTitle = nextStringOrNull(reader);
                        break;
                    case "media_id":
                        mediaId = nextStringOrNull(reader);
                        break;
                    case "thumbnail":
                        thumbnail = nextStringOrNull(reader);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }

        public int getId() {
            return id;
        }

        public int getPageCount() {
            return numPages;
        }

        public String getTitle() {
            if (englishTitle != null && !englishTitle.isEmpty()) return englishTitle;
            return japaneseTitle == null ? "" : japaneseTitle;
        }

        /**
         * The API already returns a host-relative path that includes {@code galleries/<media_id>/}
         * (e.g. {@code galleries/4119812/thumb.webp}), so only the thumb host is prepended -
         * the same convention as {@link com.maxwai.nclientv3.api.components.Page}.
         */
        @Nullable
        public Uri getThumbnail() {
            if (thumbnail == null || thumbnail.isEmpty()) return null;
            if (thumbnail.startsWith("http")) return Uri.parse(thumbnail);
            String path = thumbnail.startsWith("/") ? thumbnail.substring(1) : thumbnail;
            return Uri.parse(String.format(Locale.US, "https://%s/%s", Utility.getThumbHost(), path));
        }
    }

    /**
     * A comment preview from the profile, carrying the gallery it was posted on.
     */
    public static class RecentComment {
        private int id, galleryId;
        private String body, galleryTitle;
        private Date postDate;

        RecentComment(JsonReader reader) throws IOException {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "id":
                        id = reader.nextInt();
                        break;
                    case "gallery_id":
                        galleryId = reader.nextInt();
                        break;
                    case "body":
                        body = nextStringOrNull(reader);
                        break;
                    case "gallery_title":
                        galleryTitle = nextStringOrNull(reader);
                        break;
                    case "post_date":
                        postDate = new Date(reader.nextLong() * 1000);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }

        public int getId() {
            return id;
        }

        public int getGalleryId() {
            return galleryId;
        }

        public String getBody() {
            return body == null ? "" : body;
        }

        public String getGalleryTitle() {
            return galleryTitle == null ? "" : galleryTitle;
        }

        @Nullable
        public Date getPostDate() {
            return postDate;
        }
    }
}
