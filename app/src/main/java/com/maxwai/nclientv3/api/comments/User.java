package com.maxwai.nclientv3.api.comments;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.JsonReader;
import android.util.JsonToken;

import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.util.Locale;

public class User implements Parcelable {
    public static final Creator<User> CREATOR = new Creator<>() {
        @Override
        public User createFromParcel(Parcel in) {
            return new User(in);
        }

        @Override
        public User[] newArray(int size) {
            return new User[size];
        }
    };
    private int id;
    private String username, slug, avatarUrl;
    private boolean isSuperuser, isStaff;

    public User(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.peek() != JsonToken.END_OBJECT) {
            switch (reader.nextName()) {
                case "id":
                    id = reader.nextInt();
                    break;
                case "username":
                    username = reader.nextString();
                    break;
                case "slug":
                    slug = reader.nextString();
                    break;
                case "avatar_url":
                    avatarUrl = reader.nextString();
                    break;
                case "is_superuser":
                    isSuperuser = reader.nextBoolean();
                    break;
                case "is_staff":
                    isStaff = reader.nextBoolean();
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
    }

    protected User(Parcel in) {
        id = in.readInt();
        username = in.readString();
        slug = in.readString();
        avatarUrl = in.readString();
        isSuperuser = in.readByte() != 0;
        isStaff = in.readByte() != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(username);
        dest.writeString(slug);
        dest.writeString(avatarUrl);
        dest.writeByte((byte) (isSuperuser ? 1 : 0));
        dest.writeByte((byte) (isStaff ? 1 : 0));
    }

    public int getId() {
        return id;
    }

    public Uri getAvatarUrl() {
        return Uri.parse(String.format(Locale.US, "https://i.%s/%s", Utility.getHost(), avatarUrl));
    }

    public String getUsername() {
        return username;
    }
}
