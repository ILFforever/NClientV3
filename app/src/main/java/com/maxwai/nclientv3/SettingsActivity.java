package com.maxwai.nclientv3;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.maxwai.nclientv3.async.database.export.Exporter;
import com.maxwai.nclientv3.async.database.export.Manager;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.SimpleGallery;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.GeneralActivity;
import com.maxwai.nclientv3.components.views.GeneralPreferenceFragment;
import com.maxwai.nclientv3.settings.Database;
import com.maxwai.nclientv3.settings.Global;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class SettingsActivity extends GeneralActivity {
    GeneralPreferenceFragment fragment;
    private ActivityResultLauncher<String> IMPORT_ZIP;
    private ActivityResultLauncher<String> SAVE_SETTINGS;
    private ActivityResultLauncher<Object> REQUEST_STORAGE_MANAGER;
    private ActivityResultLauncher<String> COPY_LOGS;
    private int selectedItem;

    private void acquireWakeLock() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LogUtility.d("FLAG_KEEP_SCREEN_ON set");
    }

    private void releaseWakeLock() {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LogUtility.d("FLAG_KEEP_SCREEN_ON cleared");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerActivities();
        //Global.initActivity(this);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setTitle(R.string.settings);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        fragment = Objects.requireNonNull((GeneralPreferenceFragment) getSupportFragmentManager().findFragmentById(R.id.fragment));
        fragment.setAct(this);
        fragment.setType(SettingsActivity.Type.values()[getIntent().getIntExtra(getPackageName() + ".TYPE", SettingsActivity.Type.MAIN.ordinal())]);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
    }

    private void registerActivities() {
        IMPORT_ZIP = registerForActivityResult(new ActivityResultContracts.GetContent(), selectedFile -> {
            if (selectedFile == null) return;
            importSettings(selectedFile);
        });
        SAVE_SETTINGS = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip") {
            @NonNull
            @Override
            public Intent createIntent(@NonNull Context context, @NonNull String input) {
                Intent i = super.createIntent(context, input);
                i.setType("application/zip");
                return i;
            }
        }, selectedFile -> {
            if (selectedFile == null) return;

            exportSettings(selectedFile);

        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            REQUEST_STORAGE_MANAGER = registerForActivityResult(new ActivityResultContract<>() {

                @RequiresApi(api = Build.VERSION_CODES.R)
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, Object input) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    return i;
                }

                @Override
                public Object parseResult(int resultCode, @Nullable Intent intent) {
                    return null;
                }
            }, result -> {
                if (Global.isExternalStorageManager()) {
                    fragment.manageCustomPath();
                }
            });
        }
        COPY_LOGS = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/log") {
            @NonNull
            @Override
            public Intent createIntent(@NonNull Context context, @NonNull String input) {
                Intent i = super.createIntent(context, input);
                i.setType("text/log");
                return i;
            }
        }, selectedFile -> {
            if (selectedFile == null) return;
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d"});
                try (OutputStream outputStream = getContentResolver().openOutputStream(selectedFile);
                     Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                     BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String output = in.lines().collect(Collectors.joining("\n"));
                    writer.write(output);
                }
                Toast.makeText(this, getString(process.exitValue() != 0 ? R.string.copy_logs_fail : R.string.export_finished), Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                LogUtility.e("Error getting logcat", e);
                Toast.makeText(this, getString(R.string.copy_logs_fail), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void importSettings(Uri selectedFile) {
        acquireWakeLock();
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = getLayoutInflater().cloneInContext(builder.getContext())
            .inflate(R.layout.dialog_migration_progress, null);
        TextView countView = dialogView.findViewById(R.id.migration_count);
        ProgressBar progressBar = dialogView.findViewById(R.id.migration_progress);

        AlertDialog progressDialog = builder
            .setTitle(R.string.importing_backup)
            .setView(dialogView)
            .setCancelable(false)
            .create();
        progressDialog.show();

        final boolean[] refreshNeeded = {false};
        Manager.MigrationListener listener = new Manager.MigrationListener() {
            @Override
            public void onImportStart() {
                progressDialog.setTitle(R.string.importing_backup);
                countView.setVisibility(View.VISIBLE);
                countView.setText(getString(R.string.importing_rows_count, 0));
                progressBar.setIndeterminate(true);
            }

            @Override
            public void onImportProgress(int count) {
                countView.setText(getString(R.string.importing_rows_count, count));
            }

            @Override
            public void onOldFormatGalleriesDetected(ArrayList<Integer> galleryIds) {
                refreshNeeded[0] = true;
                Runnable onComplete = () -> {
                    releaseWakeLock();
                    progressDialog.dismiss();
                    Toast.makeText(SettingsActivity.this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                    finish();
                };
                new MaterialAlertDialogBuilder(SettingsActivity.this)
                    .setTitle(R.string.old_format_detected_title)
                    .setMessage(getString(R.string.old_format_detected_message, galleryIds.size()))
                    .setCancelable(false)
                    .setPositiveButton(R.string.process_now, (dialog, which) ->
                        refreshOldFormatGalleriesInBatches(galleryIds, progressBar, countView, progressDialog, onComplete))
                    .setNegativeButton(R.string.process_later, (dialog, which) -> onComplete.run())
                    .show();
            }

            @Override
            public void onImportComplete(int totalRows, int totalFavorites) {
                LogUtility.d("Import complete: " + totalRows + " rows, " + totalFavorites + " favorites");
                if (!refreshNeeded[0]) {
                    releaseWakeLock();
                    progressDialog.dismiss();
                    Toast.makeText(SettingsActivity.this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        };

        new Manager(selectedFile, this, false, null, listener).start();
    }

    private void exportSettings(Uri selectedFile) {
        new Manager(selectedFile, this, true, () -> Toast.makeText(this, R.string.export_finished, Toast.LENGTH_SHORT).show()).start();
    }

    public void importSettings() {
        if (IMPORT_ZIP != null) {
            IMPORT_ZIP.launch("application/zip");
        } else {
            importOldVersion();
        }
    }

    private void importOldVersion() {
        String[] files = Global.BACKUPFOLDER.list();
        if (files == null || files.length == 0) return;
        selectedItem = 0;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setSingleChoiceItems(files, 0, (dialog, which) -> {
            LogUtility.d(which);
            selectedItem = which;
        });

        builder.setPositiveButton(R.string.ok, (dialog, which) -> importSettings(Uri.fromFile(new File(Global.BACKUPFOLDER, files[selectedItem])))).setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    /** Returns the wait time in ms from a 429 response's Retry-After header, defaulting to 30s. */
    private static long getRetryAfterMs(okhttp3.Response resp) {
        String header = resp.header("Retry-After");
        if (header != null) {
            try {
                return Long.parseLong(header.trim()) * 1000;
            } catch (NumberFormatException ignored) {}
        }
        return 30_000;
    }

    /** Reads only the title from the DB without constructing a full Gallery object.
     *  Avoids triggering the API call that readPagePath() makes for old-format entries. */
    private String getGalleryTitle(int galleryId) {
        SQLiteDatabase db = Database.getDatabase();
        if (db == null) return "Unknown";
        try (Cursor c = db.query(Queries.GalleryTable.TABLE_NAME,
                new String[]{Queries.GalleryTable.TITLE_PRETTY},
                Queries.GalleryTable.IDGALLERY + "=?",
                new String[]{String.valueOf(galleryId)}, null, null, null)) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        return "Unknown";
    }

    private void refreshOldFormatGalleriesInBatches(ArrayList<Integer> galleryIds, ProgressBar progressBar, TextView countView, AlertDialog progressDialog, Runnable onComplete) {
        new Thread(() -> {
            int batchSize = 10;
            int total = galleryIds.size();
            int successful = 0;
            int failed = 0;
            int deleted = 0;
            ArrayList<String> failedGalleryNames = new ArrayList<>();
            ArrayList<String> deletedGalleryNames = new ArrayList<>();
            ArrayList<Integer> retryGalleryIds = new ArrayList<>();
            Handler handler = new Handler(Looper.getMainLooper());

            handler.post(() -> {
                progressDialog.setTitle(R.string.refreshing_galleries);
                countView.setVisibility(View.VISIBLE);
                countView.setText(getString(R.string.refreshing_galleries_progress, 0, total));
                progressBar.setIndeterminate(false);
                progressBar.setMax(total);
                progressBar.setProgress(0);
            });

            int batchItemCount = 0;
            for (int j = 0; j < total; j++) {
                int galleryId = galleryIds.get(j);
                // Read title directly from DB to avoid triggering readPagePath()'s implicit API call
                String galleryName = getGalleryTitle(galleryId);
                boolean rateLimitedThisItem = false;
                try {
                    String detailUrl = Utility.getBaseUrl() + "api/v2/galleries/" + galleryId;
                    try (okhttp3.Response resp = Global.getClient(this).newCall(new okhttp3.Request.Builder().url(detailUrl).build()).execute()) {
                        okhttp3.ResponseBody respBody = resp.body();
                        String body = respBody != null ? respBody.string() : "";
                        int statusCode = resp.code();
                        if (statusCode == HttpURLConnection.HTTP_OK) {
                            Gallery gallery = new Gallery(this, body, null, false);
                            Queries.GalleryTable.insert(gallery);
                            successful++;
                            LogUtility.d("Refreshed gallery: " + galleryId);
                        } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND || statusCode == 410) {
                            deleted++;
                            deletedGalleryNames.add(galleryName);
                            Queries.GalleryTable.delete(galleryId);
                            LogUtility.w("Gallery " + galleryId + " (" + galleryName + ") not found on server (HTTP " + statusCode + "), deleted from DB");
                        } else if (statusCode == 429 || body.contains("Rate limit exceeded")) {
                            long waitMs = getRetryAfterMs(resp);
                            LogUtility.w("Rate limit hit at gallery " + galleryId + " (" + galleryName + "), pausing " + waitMs + "ms then retrying");
                            rateLimitedThisItem = true;
                            handler.post(() -> {
                                if (progressDialog.isShowing()) {
                                    countView.setText(R.string.rate_limit_wait);
                                    progressBar.setIndeterminate(true);
                                }
                            });
                            try { Thread.sleep(waitMs); } catch (InterruptedException e) { LogUtility.e(e); }
                            final int progressSnapshot = j;
                            handler.post(() -> {
                                if (progressDialog.isShowing()) {
                                    countView.setText(getString(R.string.refreshing_galleries_progress, progressSnapshot, total));
                                    progressBar.setIndeterminate(false);
                                    progressBar.setProgress(progressSnapshot);
                                }
                            });
                            j--; // retry same gallery after the wait
                            batchItemCount = 0;
                        } else {
                            failed++;
                            failedGalleryNames.add(galleryName);
                            retryGalleryIds.add(galleryId);
                            LogUtility.w("Gallery " + galleryId + " (" + galleryName + ") fetch failed with HTTP " + statusCode + ", will retry");
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    failed++;
                    failedGalleryNames.add(galleryName);
                    retryGalleryIds.add(galleryId);
                    LogUtility.w("Gallery " + galleryId + " (" + galleryName + ") fetch timed out, will retry: " + e.getMessage());
                } catch (java.net.UnknownHostException e) {
                    failed++;
                    failedGalleryNames.add(galleryName);
                    retryGalleryIds.add(galleryId);
                    LogUtility.w("Gallery " + galleryId + " (" + galleryName + ") network error (no connection), will retry: " + e.getMessage());
                } catch (Exception e) {
                    failed++;
                    failedGalleryNames.add(galleryName);
                    retryGalleryIds.add(galleryId);
                    LogUtility.e("Failed to refresh gallery " + galleryId + " (" + galleryName + "), will retry: " + e.getMessage(), e);
                }

                if (rateLimitedThisItem) continue; // skip progress update and delays — j was decremented

                final int finalItemProgress = j + 1;
                handler.post(() -> {
                    if (progressDialog.isShowing()) {
                        countView.setText(getString(R.string.refreshing_galleries_progress, finalItemProgress, total));
                        progressBar.setProgress(finalItemProgress);
                    }
                });

                // Small per-request delay to avoid burst-triggering rate limits
                try { Thread.sleep(200); } catch (InterruptedException e) { LogUtility.e(e); }

                // Inter-batch delay every batchSize items
                batchItemCount++;
                if (batchItemCount >= batchSize && j + 1 < total) {
                    batchItemCount = 0;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { LogUtility.e(e); }
                }
            }

            final int finalSuccessful = successful;
            final int finalFailed = failed;
            final int finalDeleted = deleted;
            final ArrayList<String> finalFailedNames = failedGalleryNames;
            final ArrayList<String> finalDeletedNames = deletedGalleryNames;
            final ArrayList<Integer> finalRetryIds = retryGalleryIds;

            handler.post(() -> {
                LogUtility.i("Completed refreshing " + total + " old-format galleries: " + finalSuccessful + " successful, " + finalFailed + " failed (will retry), " + finalDeleted + " deleted");
                showImportSummaryDialog(total, finalSuccessful, finalFailed, finalDeleted, finalFailedNames, finalDeletedNames, finalRetryIds, progressDialog, onComplete);
            });
        }).start();
    }

    private void retryFailedFetches(ArrayList<Integer> galleryIds, ProgressBar progressBar, TextView countView, AlertDialog progressDialog, Runnable onComplete) {
        new Thread(() -> {
            int batchSize = 5;
            int total = galleryIds.size();
            int successful = 0;
            int stillFailed = 0;
            int deleted = 0;
            ArrayList<String> stillFailedNames = new ArrayList<>();
            ArrayList<Integer> stillFailedIds = new ArrayList<>();
            ArrayList<String> deletedNames = new ArrayList<>();
            Handler handler = new Handler(Looper.getMainLooper());

            handler.post(() -> {
                progressDialog.setTitle(R.string.retrying_galleries);
                countView.setText(getString(R.string.retrying_galleries_progress, 0, total));
                progressBar.setIndeterminate(false);
                progressBar.setMax(total);
                progressBar.setProgress(0);
            });

            int batchItemCount = 0;
            for (int j = 0; j < total; j++) {
                int galleryId = galleryIds.get(j);
                // Read title directly from DB to avoid triggering readPagePath()'s implicit API call
                String galleryName = getGalleryTitle(galleryId);
                boolean rateLimitedThisItem = false;
                try {
                    String detailUrl = Utility.getBaseUrl() + "api/v2/galleries/" + galleryId;
                    try (okhttp3.Response resp = Global.getClient(this).newCall(new okhttp3.Request.Builder().url(detailUrl).build()).execute()) {
                        okhttp3.ResponseBody respBody = resp.body();
                        String body = respBody != null ? respBody.string() : "";
                        int statusCode = resp.code();
                        if (statusCode == HttpURLConnection.HTTP_OK) {
                            Gallery gallery = new Gallery(this, body, null, false);
                            Queries.GalleryTable.insert(gallery);
                            successful++;
                            LogUtility.d("Retry succeeded for gallery: " + galleryId);
                        } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND || statusCode == 410) {
                            deleted++;
                            deletedNames.add(galleryName);
                            Queries.GalleryTable.delete(galleryId);
                            LogUtility.w("Gallery " + galleryId + " (" + galleryName + ") deleted from server during retry (HTTP " + statusCode + ")");
                        } else if (statusCode == 429 || body.contains("Rate limit exceeded")) {
                            long waitMs = getRetryAfterMs(resp);
                            LogUtility.w("Rate limit hit during retry at gallery " + galleryId + " (" + galleryName + "), pausing " + waitMs + "ms then retrying");
                            rateLimitedThisItem = true;
                            handler.post(() -> {
                                if (progressDialog.isShowing()) {
                                    countView.setText(R.string.rate_limit_wait);
                                    progressBar.setIndeterminate(true);
                                }
                            });
                            try { Thread.sleep(waitMs); } catch (InterruptedException e) { LogUtility.e(e); }
                            final int progressSnapshot = j;
                            handler.post(() -> {
                                if (progressDialog.isShowing()) {
                                    countView.setText(getString(R.string.retrying_galleries_progress, progressSnapshot, total));
                                    progressBar.setIndeterminate(false);
                                    progressBar.setProgress(progressSnapshot);
                                }
                            });
                            j--; // retry same gallery after the wait
                            batchItemCount = 0;
                        } else {
                            stillFailed++;
                            stillFailedNames.add(galleryName);
                            stillFailedIds.add(galleryId);
                            LogUtility.w("Retry failed for gallery " + galleryId + " (" + galleryName + ") with HTTP " + statusCode);
                        }
                    }
                } catch (Exception e) {
                    stillFailed++;
                    stillFailedNames.add(galleryName);
                    stillFailedIds.add(galleryId);
                    LogUtility.e("Retry failed for gallery " + galleryId + " (" + galleryName + "): " + e.getMessage(), e);
                }

                if (rateLimitedThisItem) continue; // skip progress update and delays — j was decremented

                final int finalItemProgress = j + 1;
                handler.post(() -> {
                    if (progressDialog.isShowing()) {
                        countView.setText(getString(R.string.retrying_galleries_progress, finalItemProgress, total));
                        progressBar.setProgress(finalItemProgress);
                    }
                });

                // Small per-request delay to avoid burst-triggering rate limits
                try { Thread.sleep(200); } catch (InterruptedException e) { LogUtility.e(e); }

                // Inter-batch delay every batchSize items
                batchItemCount++;
                if (batchItemCount >= batchSize && j + 1 < total) {
                    batchItemCount = 0;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { LogUtility.e(e); }
                }
            }

            final int finalSuccessful = successful;
            final int finalStillFailed = stillFailed;
            final int finalDeleted = deleted;
            final ArrayList<String> finalStillFailedNames = stillFailedNames;
            final ArrayList<Integer> finalStillFailedIds = stillFailedIds;
            final ArrayList<String> finalDeletedNames = deletedNames;

            handler.post(() -> {
                LogUtility.i("Retry complete: " + finalSuccessful + " recovered, " + finalStillFailed + " still failed, " + finalDeleted + " deleted");
                showRetrySummaryDialog(finalSuccessful, finalStillFailed, finalStillFailedNames, finalStillFailedIds, finalDeleted, finalDeletedNames, progressDialog, onComplete);
            });
        }).start();
    }

    private void showImportSummaryDialog(int total, int successful, int failed, int deleted, ArrayList<String> failedNames, ArrayList<String> deletedNames, ArrayList<Integer> retryIds, AlertDialog progressDialog, Runnable onComplete) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_summary, null);

        TextView totalView = dialogView.findViewById(R.id.summary_total);
        TextView successfulView = dialogView.findViewById(R.id.summary_successful);
        TextView failedView = dialogView.findViewById(R.id.summary_failed);
        TextView deletedView = dialogView.findViewById(R.id.summary_deleted);
        TextView missingListTitle = dialogView.findViewById(R.id.summary_missing_title);
        TextView missingListText = dialogView.findViewById(R.id.summary_missing_list);
        TextView deletedListTitle = dialogView.findViewById(R.id.summary_deleted_title);
        TextView deletedListText = dialogView.findViewById(R.id.summary_deleted_list);

        totalView.setText(String.valueOf(total));
        successfulView.setText(String.valueOf(successful));
        failedView.setText(String.valueOf(failed));
        deletedView.setText(String.valueOf(deleted));

        boolean hasFailed = failed > 0;
        boolean hasDeleted = deleted > 0;

        if (hasFailed) {
            missingListTitle.setText(getString(R.string.summary_failed_title));
            missingListTitle.setVisibility(View.VISIBLE);
            missingListText.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < failedNames.size(); i++) {
                sb.append("• ").append(failedNames.get(i));
                if (i < failedNames.size() - 1) sb.append("\n");
            }
            missingListText.setText(sb.toString());
        } else {
            missingListTitle.setVisibility(View.GONE);
            missingListText.setVisibility(View.GONE);
        }

        if (hasDeleted) {
            deletedListTitle.setVisibility(View.VISIBLE);
            deletedListText.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < deletedNames.size(); i++) {
                sb.append("• ").append(deletedNames.get(i));
                if (i < deletedNames.size() - 1) sb.append("\n");
            }
            deletedListText.setText(sb.toString());
        } else {
            deletedListTitle.setVisibility(View.GONE);
            deletedListText.setVisibility(View.GONE);
        }

        builder.setTitle(R.string.import_summary_title)
            .setView(dialogView);

        if (hasFailed) {
            builder.setPositiveButton(R.string.retry_failed, (dialog, which) -> {
                dialog.dismiss();
                retryFailedFetches(retryIds, progressDialog.findViewById(R.id.migration_progress), progressDialog.findViewById(R.id.migration_count), progressDialog, onComplete);
            })
            .setNegativeButton(R.string.skip_retry, (dialog, which) -> {
                dialog.dismiss();
                progressDialog.dismiss();
                Toast.makeText(this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                finish();
            });
        } else {
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                dialog.dismiss();
                progressDialog.dismiss();
                Toast.makeText(this, R.string.import_finished, Toast.LENGTH_SHORT).show();
                finish();
            });
        }

        builder.setCancelable(false).show();
    }

    private void showRetrySummaryDialog(int successful, int stillFailed, ArrayList<String> stillFailedNames,
                                         ArrayList<Integer> stillFailedIds, int deleted, ArrayList<String> deletedNames,
                                         AlertDialog progressDialog, Runnable onComplete) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_summary, null);

        TextView totalView = dialogView.findViewById(R.id.summary_total);
        TextView successfulView = dialogView.findViewById(R.id.summary_successful);
        TextView failedView = dialogView.findViewById(R.id.summary_failed);
        TextView deletedView = dialogView.findViewById(R.id.summary_deleted);
        TextView missingListTitle = dialogView.findViewById(R.id.summary_missing_title);
        TextView missingListText = dialogView.findViewById(R.id.summary_missing_list);
        TextView deletedListTitle = dialogView.findViewById(R.id.summary_deleted_title);
        TextView deletedListText = dialogView.findViewById(R.id.summary_deleted_list);

        totalView.setText(String.valueOf(successful + stillFailed + deleted));
        successfulView.setText(String.valueOf(successful));
        failedView.setText(String.valueOf(stillFailed));
        deletedView.setText(String.valueOf(deleted));
        deletedView.setVisibility(deleted > 0 ? View.VISIBLE : View.GONE);

        if (stillFailed > 0) {
            missingListTitle.setText(getString(R.string.summary_still_failed_title));
            missingListTitle.setVisibility(View.VISIBLE);
            missingListText.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stillFailedNames.size(); i++) {
                sb.append("• ").append(stillFailedNames.get(i));
                if (i < stillFailedNames.size() - 1) sb.append("\n");
            }
            missingListText.setText(sb.toString());
        } else {
            missingListTitle.setVisibility(View.GONE);
            missingListText.setVisibility(View.GONE);
        }

        if (deleted > 0) {
            deletedListTitle.setVisibility(View.VISIBLE);
            deletedListText.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < deletedNames.size(); i++) {
                sb.append("• ").append(deletedNames.get(i));
                if (i < deletedNames.size() - 1) sb.append("\n");
            }
            deletedListText.setText(sb.toString());
        } else {
            deletedListTitle.setVisibility(View.GONE);
            deletedListText.setVisibility(View.GONE);
        }

        builder.setTitle(R.string.retry_summary_title)
            .setView(dialogView);

        if (stillFailed > 0) {
            builder.setPositiveButton(R.string.retry_failed, (dialog, which) -> {
                dialog.dismiss();
                retryFailedFetches(stillFailedIds, progressDialog.findViewById(R.id.migration_progress), progressDialog.findViewById(R.id.migration_count), progressDialog, onComplete);
            })
            .setNegativeButton(R.string.skip_retry, (dialog, which) -> {
                dialog.dismiss();
                progressDialog.dismiss();
                if (onComplete != null) onComplete.run();
            });
        } else {
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                dialog.dismiss();
                progressDialog.dismiss();
                if (onComplete != null) onComplete.run();
            });
        }

        builder.setCancelable(false).show();
        // NOTE: onComplete is intentionally NOT called here — it is called by the button
        // handler above, after the user dismisses the dialog.
    }

    public void exportSettings() {
        String name = Exporter.defaultExportName(this);
        if (SAVE_SETTINGS != null)
            SAVE_SETTINGS.launch(name);
        else {
            File f = new File(Global.BACKUPFOLDER, name);
            exportSettings(Uri.fromFile(f));
        }
    }

    public void exportLogs() {
        if (COPY_LOGS == null) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Date actualTime = new Date();
        COPY_LOGS.launch(String.format("NClientv3_Log_%s.log", new SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()).format(actualTime)));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    public void requestStorageManager() {
        if (REQUEST_STORAGE_MANAGER == null) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show();
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setIcon(R.drawable.ic_file);
        builder.setTitle(R.string.requesting_storage_access);
        builder.setMessage(R.string.request_storage_manager_summary);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> REQUEST_STORAGE_MANAGER.launch(null)).setNegativeButton(R.string.cancel, null).show();
    }

    public enum Type {MAIN, COLUMN, DATA}

}
