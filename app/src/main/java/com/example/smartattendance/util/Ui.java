package com.example.smartattendance.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.example.smartattendance.MainActivity;
import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.text.SimpleDateFormat;
import java.util.Locale;

public final class Ui {
    private Ui() {}

    /** Stable per-app, per-phone id. Survives reinstalls; changes on factory reset. */
    public static String deviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * Screens other than MainActivity need the cached profile. After Android kills the process it is
     * gone, so send the user back through MainActivity, which reloads it.
     */
    public static boolean ensureProfile(Activity activity) {
        if (ProxyProofApp.profile != null) return true;
        activity.startActivity(new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
        return false;
    }

    public static void snack(View anyView, CharSequence message) {
        Snackbar.make(anyView, message, Snackbar.LENGTH_LONG).show();
    }

    /** Turns Firebase failures into a sentence a student can act on. */
    public static String explain(Context c, Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) return c.getString(R.string.error_offline);
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return c.getString(R.string.error_denied);
        }
        String msg = e == null ? null : e.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("network")) return c.getString(R.string.error_offline);
        return msg != null ? msg : c.getString(R.string.error_generic);
    }

    public static String dateTime(Timestamp ts) {
        if (ts == null) return "";
        return new SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(ts.toDate());
    }

    public static String time(Timestamp ts) {
        if (ts == null) return "";
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(ts.toDate());
    }

    public static String percentText(Context c, int attended, int total) {
        int p = Stats.percent(attended, total);
        return p < 0 ? c.getString(R.string.no_classes_short) : p + "%";
    }

    public static int percentColor(Context c, int attended, int total) {
        if (total <= 0) return ContextCompat.getColor(c, R.color.muted);
        return ContextCompat.getColor(c, Stats.isSafe(attended, total) ? R.color.present : R.color.absent);
    }

    public static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase(Locale.getDefault());
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
