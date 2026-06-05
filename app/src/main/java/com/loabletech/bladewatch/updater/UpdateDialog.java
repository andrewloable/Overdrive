package com.overdrive.app.updater;

import android.animation.ObjectAnimator;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.overdrive.app.R;

public class UpdateDialog {

    public static void showUpdateAvailable(Context context, String currentVersion,
                                           String newVersion, String releaseNotes,
                                           Runnable onUpdate, Runnable onDismiss) {
        SpannableStringBuilder message = new SpannableStringBuilder();
        message.append("Current: v").append(currentVersion);
        message.append("\nNew: v").append(newVersion);
        message.append("\n\n");
        message.append(markdownToSpannable(releaseNotes));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Overdrive_M3_Dialog)
                .setTitle("\uD83D\uDE80 Update Available")
                .setMessage(message)
                .setPositiveButton("Update Now", (d, w) -> { d.dismiss(); onUpdate.run(); })
                .setNegativeButton("Later", (d, w) -> { d.dismiss(); if (onDismiss != null) onDismiss.run(); })
                .setCancelable(true)
                .show();
    }

    public static ProgressHandle showProgress(Context context, Runnable onCancel) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null);
        TextView statusText = view.findViewById(R.id.updateStatusText);
        ProgressBar progressBar = view.findViewById(R.id.updateProgressBar);
        TextView percentText = view.findViewById(R.id.updatePercentText);

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Overdrive_M3_Dialog)
                .setTitle("\u2B07\uFE0F Updating Overdrive")
                .setView(view)
                .setNegativeButton("Cancel", (d, w) -> { if (onCancel != null) onCancel.run(); d.dismiss(); })
                .setCancelable(false)
                .show();

        return new ProgressHandle(dialog, statusText, progressBar, percentText);
    }

    static SpannableStringBuilder markdownToSpannable(String markdown) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (markdown == null || markdown.isEmpty()) return sb;
        for (String line : markdown.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append("\n"); continue; }
            if (t.startsWith("###")) {
                String text = t.replaceFirst("^#{1,3}\\s*", "");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.1f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.startsWith("##")) {
                String text = t.replaceFirst("^#{1,2}\\s*", "");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.2f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.startsWith("* ") || t.startsWith("- ")) {
                String text = t.substring(2).replaceAll("\\*\\*(.+?)\\*\\*", "$1");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new BulletSpan(16), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.matches("^-{3,}$")) { sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"); continue; }
            sb.append(t.replaceAll("\\*\\*(.+?)\\*\\*", "$1")).append("\n");
        }
        return sb;
    }

    public static class ProgressHandle {
        private final AlertDialog dialog;
        private final TextView statusText;
        private final ProgressBar progressBar;
        private final TextView percentText;

        ProgressHandle(AlertDialog dialog, TextView statusText,
                       ProgressBar progressBar, TextView percentText) {
            this.dialog = dialog;
            this.statusText = statusText;
            this.progressBar = progressBar;
            this.percentText = percentText;
        }

        /** Animate progress bar to target percentage with status text. */
        public void setStep(String status, int targetPercent) {
            statusText.setText(status);
            percentText.setText(targetPercent + "%");
            ObjectAnimator anim = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), targetPercent);
            anim.setDuration(600);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.start();
        }

        public void setStatus(String status) {
            statusText.setText(status);
        }

        public void setProgress(int percent) {
            progressBar.setProgress(percent);
            percentText.setText(percent + "%");
        }

        public void setIndeterminate(String status) {
            statusText.setText(status);
        }

        public void dismiss() {
            dialog.dismiss();
        }

        public void showError(String error) {
            statusText.setText("\u274C " + error);
            percentText.setText("");
            progressBar.setProgress(0);
            dialog.setCancelable(true);
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", (d, w) -> d.dismiss());
        }
    }
}
