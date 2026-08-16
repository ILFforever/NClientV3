package com.maxwai.nclientv3.components;

import androidx.annotation.NonNull;

import com.franmontiel.persistentcookiejar.ClearableCookieJar;
import com.franmontiel.persistentcookiejar.cache.CookieCache;
import com.franmontiel.persistentcookiejar.persistence.CookiePersistor;
import com.maxwai.nclientv3.utility.Utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

public class CustomCookieJar implements ClearableCookieJar {
    private final CookieCache cache;
    private final CookiePersistor persistor;

    public CustomCookieJar(CookieCache cache, CookiePersistor persistor) {
        this.cache = cache;
        this.persistor = persistor;

        this.cache.addAll(persistor.loadAll());
    }

    private static List<Cookie> filterPersistentCookies(List<Cookie> cookies) {
        List<Cookie> persistentCookies = new ArrayList<>();

        for (Cookie cookie : cookies) {
            if (cookie.persistent()) {
                persistentCookies.add(cookie);
            }
        }
        return persistentCookies;
    }

    private static boolean isCookieExpired(Cookie cookie) {
        return cookie.expiresAt() < System.currentTimeMillis();
    }

    @Override
    synchronized public void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> cookies) {
        cache.addAll(cookies);
        persistor.saveAll(filterPersistentCookies(cookies));
    }

    /**
     * Unlike the upstream jar this does not match each cookie against the request URL, because
     * session cookies are stored host-only against whichever mirror was active when they were set
     * and would then stop being sent the moment the user switched mirrors, or asked for a
     * subdomain. The site check below is what keeps that leniency from reaching third parties:
     * the same client also fetches GitHub releases, and login cookies have no business going
     * there. See {@link Utility#isSiteHost}.
     */
    @NonNull
    @Override
    synchronized public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        List<Cookie> cookiesToRemove = new ArrayList<>();
        List<Cookie> validCookies = new ArrayList<>();
        boolean ownSite = Utility.isSiteHost(url.host());

        for (Iterator<Cookie> it = cache.iterator(); it.hasNext(); ) {
            Cookie currentCookie = it.next();

            if (isCookieExpired(currentCookie)) {
                cookiesToRemove.add(currentCookie);
                it.remove();

            } else if (ownSite) {
                validCookies.add(currentCookie);
            }
        }

        persistor.removeAll(cookiesToRemove);
        return validCookies;
    }

    @Override
    synchronized public void clearSession() {
        cache.clear();
        cache.addAll(persistor.loadAll());
    }

    @Override
    synchronized public void clear() {
        cache.clear();
        persistor.clear();
    }

    public void removeCookie(String name) {
        List<Cookie> cookies = persistor.loadAll();
        for (Cookie cookie : cookies) {
            if (cookie.name().equals(name)) {
                cache.clear();
                persistor.removeAll(Collections.singletonList(cookie));
            }
        }
    }
}
