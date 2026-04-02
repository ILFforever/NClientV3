package com.maxwai.nclientv3.async.database.export;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.SettingsActivity;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.ArrayList;

public class Manager extends Thread {
    /**
     * Receives callbacks during database import/refresh operations.
     * All callbacks are dispatched on the main thread.
     */
    public interface MigrationListener {
        /** Called once when database import starts. */
        void onImportStart();
        /** Called as database rows are imported. {@code count} is total rows imported so far. */
        void onImportProgress(int count);
        /** Called when old-format Gallery entries are detected (pages will refresh via API immediately after import). */
        void onOldFormatGalleriesDetected(ArrayList<Integer> galleryIds);
        /** Called after all data is imported from the backup. */
        void onImportComplete(int totalRows, int totalFavorites);
    }

    @NonNull
    private final Uri file;
    @NonNull
    private final SettingsActivity context;
    private final boolean export;
    private final Runnable end;
    @Nullable
    private final MigrationListener migrationListener;

    public Manager(@NonNull Uri file, @NonNull SettingsActivity context, boolean export, Runnable end) {
        this(file, context, export, end, null);
    }

    public Manager(@NonNull Uri file, @NonNull SettingsActivity context, boolean export,
                   Runnable end, @Nullable MigrationListener migrationListener) {
        this.file = file;
        this.context = context;
        this.export = export;
        this.end = end;
        this.migrationListener = migrationListener;
    }

    @Override
    public void run() {
        LogUtility.i("Manager: starting " + (export ? "export" : "import") + " — file=" + file);
        try {
            if (export) {
                Exporter.exportData(context, file);
                LogUtility.i("Manager: export finished");
            } else {
                Importer.importData(context, file, wrapListener(migrationListener));
                LogUtility.i("Manager: import finished");
            }
            if (end != null) context.runOnUiThread(end);
        } catch (IOException e) {
            LogUtility.e("Manager: " + (export ? "export" : "import") + " failed — " + e.getMessage(), e);
        }
    }

    /** Wraps a listener so every callback is posted to the UI thread. */
    @Nullable
    private MigrationListener wrapListener(@Nullable MigrationListener listener) {
        if (listener == null) return null;
        return new MigrationListener() {
            @Override
            public void onImportStart() {
                context.runOnUiThread(() -> listener.onImportStart());
            }

            @Override
            public void onImportProgress(int count) {
                context.runOnUiThread(() -> listener.onImportProgress(count));
            }

            @Override
            public void onOldFormatGalleriesDetected(ArrayList<Integer> galleryIds) {
                context.runOnUiThread(() -> listener.onOldFormatGalleriesDetected(galleryIds));
            }

            @Override
            public void onImportComplete(int totalRows, int totalFavorites) {
                context.runOnUiThread(() -> listener.onImportComplete(totalRows, totalFavorites));
            }
        };
    }
}
