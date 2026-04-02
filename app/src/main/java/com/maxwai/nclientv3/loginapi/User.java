package com.maxwai.nclientv3.loginapi;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class User {
    private final String username, codename, avatarUrl;
    private final int id;

    private User(String username, String id, String codename, String avatarUrl) {
        this.username = username;
        this.id = Integer.parseInt(id);
        this.codename = codename;
        this.avatarUrl = avatarUrl;
    }

    public static void createUser(@NonNull Context context, final CreateUser createUser) {
        Global.getClient(context).newCall(new Request.Builder().url(Login.BASE_HTTP_URL).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                User user = null;
                Document doc = Jsoup.parse(response.body().byteStream(), null, Utility.getBaseUrl());
                Elements usernameElements = doc.select("span.username");
                if (!usernameElements.isEmpty()) {
                    Element span = usernameElements.first();
                    Element link = Objects.requireNonNull(span).parent();
                    String username = span.text().trim();
                    String href = Objects.requireNonNull(link).attr("href");
                    String[] parts = href.split("/");
                    Element avatar = link.selectFirst("img.nav-avatar");
                    String avatarUrl = avatar != null ? avatar.attr("src") : null;
                    user = new User(username, parts[2], parts[3], avatarUrl);
                }
                Login.updateUser(user);
                if (createUser != null) createUser.onCreateUser(Login.getUser());
            }
        });
    }

    @NonNull
    @Override
    public String toString() {
        return username + '(' + id + '/' + codename + ')';
    }

    public String getUsername() {
        return username;
    }

    public int getId() {
        return id;
    }

    public String getCodename() {
        return codename;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public interface CreateUser {
        void onCreateUser(User user);
    }


}
