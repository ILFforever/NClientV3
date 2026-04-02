package com.maxwai.nclientv3.async.database.export;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.settings.Database;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


class Importer {
    private static void importSharedPreferences(Context context, String sharedName, InputStream stream) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(stream));
        if (sharedName.contains("/")) {
            String[] names = sharedName.split("/");
            sharedName = names[names.length - 1];
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(sharedName, 0).edit();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            reader.beginObject();
            Exporter.SharedType type = Exporter.SharedType.valueOf(reader.nextName());
            switch (type) {
                case STRING:
                    editor.putString(name, reader.nextString());
                    break;
                case INT:
                    editor.putInt(name, reader.nextInt());
                    break;
                case FLOAT:
                    editor.putFloat(name, (float) reader.nextDouble());
                    break;
                case LONG:
                    editor.putLong(name, reader.nextLong());
                    break;
                case BOOLEAN:
                    editor.putBoolean(name, reader.nextBoolean());
                    break;
                case STRING_SET:
                    Set<String> strings = new HashSet<>();
                    reader.beginArray();
                    while (reader.hasNext())
                        strings.add(reader.nextString());
                    reader.endArray();
                    editor.putStringSet(name, strings);
                    break;
            }
            reader.endObject();
        }
        editor.apply();
    }

    private static void importDB(InputStream stream, @Nullable Manager.MigrationListener listener) throws IOException {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null)
            throw new IOException("Can't import Database, don't have database connection yet");
        LogUtility.d("importDB: starting database import");
        if (listener != null) listener.onImportStart();
        db.beginTransaction();
        JsonReader reader = new JsonReader(new InputStreamReader(stream));
        reader.beginObject();

        // Accumulated across tables — both sets are needed before we can filter
        Set<Integer> oldFormatGalleryIds = new HashSet<>(); // Gallery rows whose pages lack '/' (need API refresh)
        Set<Integer> favoriteGalleryIds = new HashSet<>();  // IDs present in the Favorite table
        int favoriteCount = 0;
        int totalRowsImported = 0;

        while (reader.hasNext()) {
            String tableName = reader.nextName();
            LogUtility.d("importDB: processing table \"" + tableName + "\"");

            db.delete(tableName, null, null);
            int rowCount = 0;
            int insertedCount = 0;

            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                ContentValues values = new ContentValues();
                boolean rowIsOldFormat = false;

                while (reader.hasNext()) {
                    String fieldName = reader.nextName();
                    switch (reader.peek()) {
                        case NULL:
                            values.putNull(fieldName);
                            reader.nextNull();
                            break;
                        case NUMBER:
                            //there are no doubles in the DB
                            values.put(fieldName, reader.nextLong());
                            break;
                        case STRING:
                            String strVal = reader.nextString();
                            values.put(fieldName, strVal);
                            // Old-format Gallery pages: "37;webp;webp;37;webp;" has ';' but no '/'.
                            // New-format pages:         "23;/cover.webp;/1.webp;…" has both.
                            // Same check as GalleryData.readPagePath().
                            if (Queries.GalleryTable.TABLE_NAME.equals(tableName)
                                    && "pages".equals(fieldName)
                                    && strVal.contains(";") && !strVal.contains("/")) {
                                rowIsOldFormat = true;
                            }
                            break;
                        default:
                            LogUtility.w("importDB: skipping unexpected token in table \"" + tableName + "\", field \"" + fieldName + "\"");
                            reader.skipValue();
                            break;
                    }
                }

                if (Queries.FavoriteTable.TABLE_NAME.equals(tableName) && !values.containsKey("time")) {
                    LogUtility.i("importDB: Favorite row missing 'time' — defaulting to current time");
                    values.put("time", new Date().getTime());
                }

                long result = db.insertWithOnConflict(tableName, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                if (result == -1) {
                    LogUtility.e("importDB: insert failed for table \"" + tableName + "\", values=" + values);
                } else {
                    insertedCount++;
                    totalRowsImported++;
                    if (Queries.FavoriteTable.TABLE_NAME.equals(tableName)) {
                        favoriteCount++;
                        Integer favId = values.getAsInteger("id_gallery");
                        if (favId != null) favoriteGalleryIds.add(favId);
                    }
                    if (listener != null && totalRowsImported % 10 == 0) listener.onImportProgress(totalRowsImported);
                }

                if (rowIsOldFormat) {
                    Integer galleryId = values.getAsInteger(Queries.GalleryTable.IDGALLERY);
                    if (galleryId != null) {
                        oldFormatGalleryIds.add(galleryId);
                        LogUtility.d("importDB: old-format pages row detected (no '/') — galleryId=" + galleryId);
                    }
                }

                rowCount++;
                reader.endObject();
            }
            reader.endArray();
            LogUtility.d("importDB: table \"" + tableName + "\" — read " + rowCount + " rows, inserted " + insertedCount);

            if (Queries.FavoriteTable.TABLE_NAME.equals(tableName)) {
                LogUtility.i("importDB: " + favoriteCount + " favorites imported");
            }
        }

        //avoid rate limits by only updating favorites
        oldFormatGalleryIds.retainAll(favoriteGalleryIds);
        LogUtility.i("importDB: " + oldFormatGalleryIds.size() + " old-format favorited galleries need API refresh");
        if (listener != null && !oldFormatGalleryIds.isEmpty()) {
            listener.onOldFormatGalleriesDetected(new ArrayList<>(oldFormatGalleryIds));
        }

        reader.endObject();
        db.setTransactionSuccessful();
        db.endTransaction();
        if (listener != null) listener.onImportProgress(totalRowsImported);
        if (listener != null) listener.onImportComplete(totalRowsImported, favoriteCount);
        LogUtility.i("importDB: import transaction committed successfully");
    }

    public static void importData(@NonNull Context context, Uri selectedFile,
                                   @Nullable Manager.MigrationListener listener) throws IOException {
        InputStream stream = context.getContentResolver().openInputStream(selectedFile);
        try (ZipInputStream inputStream = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                String name = entry.getName();
                LogUtility.d("Importing: " + name);
                if (Exporter.DB_ZIP_FILE.equals(name)) {
                    importDB(inputStream, listener);
                } else {
                    String shared = name.substring(0, name.length() - 5);
                    importSharedPreferences(context, shared, inputStream);
                }
                inputStream.closeEntry();
            }
        }
    }
}
