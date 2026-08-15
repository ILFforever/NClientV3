package com.maxwai.nclientv3.utility;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.settings.Global;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Random;

public class Utility {
    public static final Random RANDOM = new Random(System.nanoTime());
    public static final String ORIGINAL_URL = "nhentai.net";

    public static String getBaseUrl() {
        return "https://" + Utility.getHost() + "/";
    }

    public static String getApiBaseUrl() {
        return getBaseUrl() + "api/v2/";
    }

    public static String getHost() {
        return Global.getMirror();
    }

    /**
     * Host serving full-size page images.
     * <p>
     * {@code /api/v2/config} advertises the numbered servers (i1-i4), but some ISPs DNS-sinkhole
     * those subdomains to a blocklist address, which makes every image request hang until it
     * times out while the API host itself keeps working. The unnumbered alias is not sinkholed
     * and serves the same content, so it is used here. Keep image hosts going through this
     * method rather than hardcoding a prefix, so there is one place to change.
     */
    public static String getImageHost() {
        return "i." + getHost();
    }

    /**
     * Host serving thumbnails and covers. See {@link #getImageHost()} for why this is not t1.
     */
    public static String getThumbHost() {
        return "t." + getHost();
    }

    /**
     * Rewrites a numbered CDN host in an absolute URL to the alias host. Absolute i1-i4/t1-t4
     * URLs reach us from scraped HTML and from rows written by older versions, so they never go
     * through {@link #getImageHost()} and would otherwise still hit a sinkholed subdomain.
     */
    public static String normalizeImageHost(String url) {
        if (url == null || url.isEmpty()) return url;
        String host = getHost();
        for (int i = 1; i <= 4; i++) {
            url = url.replace("://i" + i + "." + host, "://" + getImageHost());
            url = url.replace("://t" + i + "." + host, "://" + getThumbHost());
        }
        return url;
    }

    private static void parseEscapedCharacter(Reader reader, Writer writer) throws IOException {
        int toCreate, read;
        switch (read = reader.read()) {
            case 'u':
                toCreate = 0;
                for (int i = 0; i < 4; i++) {
                    toCreate *= 16;
                    toCreate += Character.digit(reader.read(), 16);
                }
                writer.write(toCreate);
                break;
            case 'n':
                writer.write('\n');
                break;
            case 't':
                writer.write('\t');
                break;
            default:
                writer.write('\\');
                writer.write(read);
                break;
        }
    }

    @NonNull
    public static String unescapeUnicodeString(@Nullable String scriptHtml) {
        if (scriptHtml == null) return "";
        StringReader reader = new StringReader(scriptHtml);
        StringWriter writer = new StringWriter();
        int actualChar;
        try {
            while ((actualChar = reader.read()) != -1) {
                if (actualChar != '\\') writer.write(actualChar);
                else parseEscapedCharacter(reader, writer);
            }
        } catch (IOException ignore) {
            return "";
        }
        return writer.toString();
    }

    public static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            LogUtility.d("Unimportant sleep interrupted", e);
        }
    }

    public static void tintMenu(Context context, Menu menu) {
        int x = menu.size();
        for (int i = 0; i < x; i++) {
            MenuItem item = menu.getItem(i);
            Global.setTint(context, item.getIcon());
        }
    }

    @Nullable
    private static Bitmap drawableToBitmap(Drawable dra) {
        if (!(dra instanceof BitmapDrawable)) return null;
        return ((BitmapDrawable) dra).getBitmap();
    }

    public static void saveImage(Drawable drawable, File output) {
        Bitmap b = drawableToBitmap(drawable);
        if (b != null) saveImage(b, output);
    }

    private static void saveImage(@NonNull Bitmap bitmap, @NonNull File output) {
        try {
            if (!output.exists())
                //noinspection ResultOfMethodCallIgnored
                output.createNewFile();
            try (FileOutputStream ostream = new FileOutputStream(output)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, ostream);
                ostream.flush();
            }
        } catch (IOException e) {
            LogUtility.e(e.getLocalizedMessage(), e);
        }
    }

    public static long writeStreamToFile(InputStream inputStream, File filePath) throws IOException {
        try (inputStream;
             FileOutputStream outputStream = new FileOutputStream(filePath)) {
            int read;
            long totalByte = 0;
            byte[] bytes = new byte[1024];
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
                totalByte += read;
            }
            outputStream.flush();
            return totalByte;
        }
    }

    public static void sendImage(Context context, Drawable drawable, String text) {
        context = context.getApplicationContext();
        try {
            File tempFile = File.createTempFile("toSend", ".jpg");
            tempFile.deleteOnExit();
            Bitmap image = drawableToBitmap(drawable);
            if (image == null) return;
            saveImage(image, tempFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            if (text != null) shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            Uri x = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", tempFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, x);
            shareIntent.setType("image/jpeg");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                context.grantUriPermission(packageName, x, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            shareIntent = Intent.createChooser(shareIntent, context.getString(R.string.share_with));
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(shareIntent);
        } catch (IOException e) {
            LogUtility.e("Error creating temp file", e);
            Toast.makeText(context, R.string.send_image_error, Toast.LENGTH_SHORT).show();
        }

    }

}
