package org.telegram.botgram;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Uses Telegram's standard list and dialogs; exports through Android's document picker. */
public final class BotgramLogsActivity extends UniversalFragment {
    private static final int SAVE_LOGS_REQUEST = 0xB071;
    private static final int PREVIEW_BYTES = 256 * 1024;
    private File[] files = new File[0];
    private boolean loading = true;
    private boolean destroyed;

    public static void showDebugMenu(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(getString(R.string.SettingsDebug))
                .setItems(new CharSequence[]{getString(R.string.BotgramSaveLogs), getString(R.string.BotgramViewLogs)},
                        (dialog, which) -> {
                            if (which == 0) saveLogs(fragment);
                            else fragment.presentFragment(new BotgramLogsActivity());
                        })
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    public static void saveLogs(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "Botgram-logs-" + System.currentTimeMillis() + ".zip");
            fragment.startActivityForResult(intent, SAVE_LOGS_REQUEST);
        } catch (Exception e) {
            showMessage(fragment, getString(R.string.ErrorOccurred));
        }
    }

    public static boolean onActivityResult(BaseFragment fragment, int requestCode, int resultCode, Intent data) {
        if (requestCode != SAVE_LOGS_REQUEST) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                || fragment.getParentActivity() == null) return true;
        Uri uri = data.getData();
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        fragment.showDialog(progress);
        FileLog.flushLogs(() -> Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            try (OutputStream destination = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "w")) {
                if (destination == null) throw new IOException("No output stream");
                try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
                    zip.putNextEntry(new ZipEntry("info.txt"));
                    zip.write(("Botgram diagnostic logs\nCreated: " + new java.util.Date() + "\n").getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    byte[] buffer = new byte[64 * 1024];
                    for (File file : listLogs()) {
                        // Snapshot length avoids chasing a file that is still being written.
                        long remaining = file.length();
                        try (FileInputStream source = new FileInputStream(file)) {
                            zip.putNextEntry(new ZipEntry(file.getName()));
                            while (remaining > 0) {
                                int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (count < 0) break;
                                zip.write(buffer, 0, count);
                                remaining -= count;
                            }
                            zip.closeEntry();
                        }
                    }
                }
                success = true;
            } catch (Exception e) {
                FileLog.e("Unable to export diagnostic logs", e);
            }
            boolean saved = success;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                showMessage(fragment, getString(saved ? R.string.BotgramLogsSaved : R.string.ErrorOccurred));
            });
        }));
        return true;
    }

    private static File[] listLogs() throws IOException {
        File directory = AndroidUtilities.getLogsDir();
        if (directory == null) throw new IOException("Log directory unavailable");
        File[] logs = directory.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".txt") || file.getName().endsWith(".log")));
        if (logs == null) throw new IOException("Cannot list logs");
        Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
        return logs;
    }

    private static void showMessage(BaseFragment fragment, CharSequence message) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(getString(R.string.SettingsDebug)).setMessage(message)
                .setPositiveButton(getString(R.string.OK), null).create());
    }

    @Override public void onResume() {
        super.onResume();
        FileLog.flushLogs(() -> Utilities.globalQueue.postRunnable(() -> {
            File[] result;
            boolean failed = false;
            try { result = listLogs(); }
            catch (IOException e) { result = new File[0]; failed = true; }
            File[] loaded = result;
            boolean error = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                files = loaded;
                loading = false;
                if (listView != null) listView.adapter.update(true);
                if (error) showMessage(this, getString(R.string.ErrorOccurred));
            });
        }));
    }

    @Override protected CharSequence getTitle() { return getString(R.string.BotgramViewLogs); }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (loading || files.length == 0) {
            items.add(UItem.asShadow(getString(loading ? R.string.Loading : R.string.BotgramNoLogs)));
        } else {
            for (int i = 0; i < files.length; i++) {
                items.add(UItem.asButton(i, files[i].getName(), AndroidUtilities.formatFileSize(files[i].length())));
            }
            items.add(UItem.asShadow(getString(R.string.BotgramLogsPreviewInfo)));
        }
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= files.length) return;
        File selected = files[item.id];
        Utilities.globalQueue.postRunnable(() -> {
            String text;
            try (RandomAccessFile input = new RandomAccessFile(selected, "r")) {
                long length = input.length();
                int count = (int) Math.min(length, PREVIEW_BYTES);
                input.seek(length - count);
                byte[] bytes = new byte[count];
                input.readFully(bytes);
                text = new String(bytes, StandardCharsets.UTF_8);
                if (length > count) {
                    int newline = text.indexOf('\n');
                    if (newline >= 0) text = text.substring(newline + 1);
                    text = getString(R.string.BotgramLogsPreviewInfo) + "\n\n" + text;
                }
                if (text.isEmpty()) text = getString(R.string.BotgramNoLogs);
            } catch (Exception e) { text = getString(R.string.ErrorOccurred); }
            String preview = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || getParentActivity() == null) return;
                AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(selected.getName()).setMessage(preview)
                        .setPositiveButton(getString(R.string.Close), null).create();
                showDialog(dialog);
                if (dialog.getMessageTextView() != null) dialog.getMessageTextView().setTextIsSelectable(true);
            });
        });
    }

    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onFragmentDestroy() { destroyed = true; super.onFragmentDestroy(); }
}
