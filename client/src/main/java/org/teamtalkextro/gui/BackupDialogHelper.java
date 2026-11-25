package org.teamtalkextro.gui;

import android.app.Activity;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.concurrent.Executor;
import org.teamtalkextro.R;
import org.teamtalkextro.data.SettingsBackupManager;

/**
 * Shared Material 3 dialog helper for optional password-encrypted JSON Backup export
 * and auto-detected password-protected JSON Backup import across ServerListActivity
 * and PreferencesActivity.
 */
public final class BackupDialogHelper {

    public interface OnImportSuccess {
        void onImported(SettingsBackupManager.ImportResult result);
    }

    private BackupDialogHelper() {
    }

    public static void promptExportPasswordAndRun(final Activity activity, final Uri uri, final Executor executor) {
        if (activity == null || activity.isFinishing() || uri == null) return;

        float d = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, Math.round(8 * d), pad, 0);

        TextView hint = new TextView(activity);
        hint.setText(R.string.backup_export_password_message);
        hint.setPadding(0, 0, 0, Math.round(10 * d));
        container.addView(hint);

        final EditText passwordInput = new EditText(activity);
        passwordInput.setHint(R.string.backup_password_hint);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setSingleLine(true);
        container.addView(passwordInput);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.backup_export_password_title)
                .setView(container)
                .setPositiveButton(R.string.backup_export_confirm, (dialog, which) -> {
                    final String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";
                    runAsync(executor, () -> {
                        SettingsBackupManager.ExportResult res = SettingsBackupManager.exportToUri(activity, uri, password);
                        activity.runOnUiThread(() -> {
                            if (res.success) {
                                int msgRes = res.encrypted
                                        ? R.string.backup_export_encrypted_success
                                        : R.string.backup_export_success;
                                Toast.makeText(activity,
                                        activity.getString(msgRes, res.serverCount, res.prefCount),
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(activity,
                                        activity.getString(R.string.backup_export_failed, res.errorMessage),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void inspectAndImport(final Activity activity, final Uri uri, final Executor executor,
                                        final OnImportSuccess onSuccess) {
        if (activity == null || activity.isFinishing() || uri == null) return;

        runAsync(executor, () -> {
            final boolean encrypted = SettingsBackupManager.isEncryptedBackupUri(activity, uri);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (encrypted) {
                    promptImportPassword(activity, uri, executor, onSuccess, null);
                } else {
                    executeImport(activity, uri, null, executor, onSuccess);
                }
            });
        });
    }

    private static void promptImportPassword(final Activity activity, final Uri uri, final Executor executor,
                                             final OnImportSuccess onSuccess, final String errorBanner) {
        if (activity == null || activity.isFinishing()) return;

        float d = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, Math.round(8 * d), pad, 0);

        TextView msg = new TextView(activity);
        msg.setText(errorBanner != null && !errorBanner.isEmpty()
                ? errorBanner
                : activity.getString(R.string.backup_import_password_message));
        msg.setPadding(0, 0, 0, Math.round(10 * d));
        container.addView(msg);

        final EditText passwordInput = new EditText(activity);
        passwordInput.setHint(R.string.backup_password_hint);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setSingleLine(true);
        container.addView(passwordInput);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.backup_import_password_title)
                .setView(container)
                .setPositiveButton(R.string.backup_decrypt_and_import, (dialog, which) -> {
                    String password = passwordInput.getText() != null ? passwordInput.getText().toString() : "";
                    executeImport(activity, uri, password, executor, onSuccess);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void executeImport(final Activity activity, final Uri uri, final String password,
                                      final Executor executor, final OnImportSuccess onSuccess) {
        runAsync(executor, () -> {
            SettingsBackupManager.ImportResult res = SettingsBackupManager.importFromUri(activity, uri, password);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (res.success) {
                    if (onSuccess != null) {
                        onSuccess.onImported(res);
                    }
                    Toast.makeText(activity,
                            activity.getString(R.string.backup_import_success, res.sourcePlatform,
                                    res.serversAdded, res.serversSkipped, res.prefsRestored),
                            Toast.LENGTH_LONG).show();
                } else if (res.passwordRequired || res.wrongPassword) {
                    Toast.makeText(activity, res.errorMessage, Toast.LENGTH_LONG).show();
                    promptImportPassword(activity, uri, executor, onSuccess, res.errorMessage);
                } else {
                    Toast.makeText(activity,
                            activity.getString(R.string.backup_import_failed, res.errorMessage),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static void runAsync(Executor executor, Runnable r) {
        if (executor != null) {
            executor.execute(r);
        } else {
            new Thread(r, "Extro-BackupIO").start();
        }
    }
}
