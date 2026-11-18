package com.example.smartattendance.faculty;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.Course;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.util.Codes;
import com.example.smartattendance.util.QrPayload;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The classroom screen. While it is open, a new random token is written to
 * sessions/{id}/secret/token every 15 seconds and shown as a QR. Firestore rules only accept
 * the current token (or the previous one for a few seconds), so a photo of the code shared
 * on WhatsApp is useless by the time anyone outside the room could scan it.
 */
public class LiveSessionActivity extends AppCompatActivity {

    public static final String EXTRA_COURSE_ID = "courseId";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_RADIUS = "radius";
    static final long ROTATE_MS = 15_000;
    private static final long RETRY_MS = 3_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateTask = this::rotate;

    private String courseId;
    private String sessionId;
    private String currentToken;
    private int enrolled;
    private boolean running;

    private ImageView qr;
    private CircularProgressIndicator ring;
    private TextView status, count, countCaption, recent;
    private ValueAnimator countdown;
    private ListenerRegistration attendanceListener;

    private final ActivityResultLauncher<String> locationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) createSession();
                else abort(getString(R.string.live_need_location));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        setContentView(R.layout.activity_live_session);
        // Bright and awake: the code has to be readable from the back benches for the whole class.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;
        getWindow().setAttributes(lp);

        courseId = getIntent().getStringExtra(EXTRA_COURSE_ID);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        qr = findViewById(R.id.image_qr);
        ring = findViewById(R.id.ring);
        status = findViewById(R.id.text_status);
        count = findViewById(R.id.text_count);
        countCaption = findViewById(R.id.text_count_caption);
        recent = findViewById(R.id.text_recent);
        count.setText("0");
        ring.setMax(1000);

        findViewById(R.id.button_end).setOnClickListener(v -> confirmEnd());
        findViewById(R.id.button_close).setOnClickListener(v -> confirmLeave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        });

        Db.col(Db.COURSES).document(courseId).get().addOnSuccessListener(snap -> {
            Course course = snap.toObject(Course.class);
            if (course != null) {
                ((TextView) findViewById(R.id.text_course_code)).setText(course.code);
                ((TextView) findViewById(R.id.text_course_name)).setText(course.name);
            }
        });
        Db.col(Db.ENROLLMENTS).whereEqualTo("courseId", courseId).get().addOnSuccessListener(snap -> {
            enrolled = snap.size();
            updateCountCaption();
        });

        if (sessionId == null) {
            status.setText(R.string.live_locating);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) createSession();
            else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (sessionId != null) begin();
    }

    @Override
    protected void onStop() {
        super.onStop();
        pause();
    }

    // Session lifecycle --------------------------------------------------------------------

    /** The faculty's position becomes the centre of the geofence students must be inside. */
    @SuppressWarnings("MissingPermission") // checked before this is called
    private void createSession() {
        LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (isDestroyed()) return;
                    if (loc == null) {
                        abort(getString(R.string.live_no_location));
                        return;
                    }
                    status.setText(R.string.live_starting);
                    Db.col(Db.COURSES).document(courseId).get().continueWithTask(t -> {
                        Course course = t.getResult().toObject(Course.class);
                        Map<String, Object> s = new HashMap<>();
                        s.put("courseId", courseId);
                        s.put("courseName", course == null ? "" : course.name);
                        s.put("courseCode", course == null ? "" : course.code);
                        s.put("facultyId", ProxyProofApp.profile.uid);
                        s.put("active", true);
                        s.put("startedAt", FieldValue.serverTimestamp());
                        s.put("endedAt", null);
                        s.put("lat", loc.getLatitude());
                        s.put("lng", loc.getLongitude());
                        // Firestore rules have no cos(), so store how many metres one degree of longitude is here;
                        // the rules then check the student's raw coordinates against the radius themselves.
                        s.put("mPerDegLng", 111_320 * Math.cos(Math.toRadians(loc.getLatitude())));
                        s.put("radiusM", getIntent().getIntExtra(EXTRA_RADIUS, 50));
                        DocumentReference ref = Db.col(Db.SESSIONS).document();
                        return ref.set(s).continueWith(x -> ref.getId());
                    }).addOnSuccessListener(id -> {
                        sessionId = id;
                        begin();
                    }).addOnFailureListener(e -> abort(Ui.explain(this, e)));
                })
                .addOnFailureListener(e -> abort(getString(R.string.live_no_location)));
    }

    private void begin() {
        if (running || isFinishing()) return;
        running = true;
        rotate();
        attendanceListener = Db.col(Db.ATTENDANCE).whereEqualTo("sessionId", sessionId)
                .whereEqualTo("facultyId", ProxyProofApp.profile.uid)
                .addSnapshotListener((snap, e) -> {
                    if (snap == null) return;
                    List<AttendanceRecord> records = snap.toObjects(AttendanceRecord.class);
                    records.sort((a, b) -> {
                        if (a.markedAt == null || b.markedAt == null) return a.markedAt == null ? -1 : 1;
                        return b.markedAt.compareTo(a.markedAt);
                    });
                    showAttendance(records);
                });
    }

    /** Leaving the screen stops the rotation; the last token expires within seconds. */
    private void pause() {
        running = false;
        handler.removeCallbacks(rotateTask);
        if (countdown != null) countdown.cancel();
        if (attendanceListener != null) attendanceListener.remove();
        attendanceListener = null;
    }

    // Token rotation -----------------------------------------------------------------------

    private void rotate() {
        if (!running) return;
        String next = Codes.token();
        Map<String, Object> secret = new HashMap<>();
        secret.put("token", next);
        secret.put("prevToken", currentToken == null ? next : currentToken);
        secret.put("tokenAt", FieldValue.serverTimestamp());
        Db.col(Db.SESSIONS).document(sessionId).collection(Db.SECRET).document(Db.TOKEN_DOC).set(secret)
                .addOnSuccessListener(x -> {
                    if (!running) return;
                    currentToken = next;
                    showQr(QrPayload.encode(sessionId, next));
                    handler.postDelayed(rotateTask, ROTATE_MS);
                })
                .addOnFailureListener(e -> {
                    if (!running) return;
                    qr.setAlpha(0.15f);
                    status.setText(R.string.live_reconnecting);
                    handler.postDelayed(rotateTask, RETRY_MS);
                });
    }

    private void showQr(String content) {
        try {
            int size = Math.max(qr.getWidth(), 600);
            Bitmap bitmap = new BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, size, size);
            qr.setImageBitmap(bitmap);
            qr.setAlpha(1f);
        } catch (WriterException e) {
            status.setText(e.getMessage());
            return;
        }
        if (countdown != null) countdown.cancel();
        countdown = ValueAnimator.ofInt(1000, 0);
        countdown.setDuration(ROTATE_MS);
        countdown.setInterpolator(new LinearInterpolator());
        countdown.addUpdateListener(a -> {
            int value = (int) a.getAnimatedValue();
            ring.setProgress(value);
            long seconds = (long) Math.ceil(value * ROTATE_MS / 1000.0 / 1000.0);
            status.setText(getString(R.string.live_countdown, seconds));
        });
        countdown.start();
    }

    // Attendance ---------------------------------------------------------------------------

    private int currentCount;

    private void showAttendance(List<AttendanceRecord> records) {
        currentCount = records.size();
        count.setText(String.valueOf(currentCount));
        updateCountCaption();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(4, records.size()); i++) {
            AttendanceRecord r = records.get(i);
            if (names.length() > 0) names.append('\n');
            names.append(r.studentName).append("   ").append(Ui.time(r.markedAt));
        }
        recent.setText(names.length() == 0 ? getString(R.string.live_waiting) : names);
    }

    private void updateCountCaption() {
        countCaption.setText(enrolled > 0
                ? getResources().getQuantityString(R.plurals.live_present_of, enrolled, enrolled)
                : getString(R.string.live_present));
    }

    // Ending -------------------------------------------------------------------------------

    private void confirmEnd() {
        if (sessionId == null) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.end_title)
                .setMessage(getResources().getQuantityString(R.plurals.end_message, enrolled, currentCount, enrolled))
                .setPositiveButton(R.string.action_end_class, (d, w) -> endSession())
                .setNegativeButton(R.string.action_keep_running, null)
                .show();
    }

    private void confirmLeave() {
        if (sessionId == null) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.leave_title)
                .setMessage(R.string.leave_message)
                .setPositiveButton(R.string.action_end_class, (d, w) -> endSession())
                .setNeutralButton(R.string.action_leave_open, (d, w) -> finish())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void endSession() {
        pause();
        Map<String, Object> update = new HashMap<>();
        update.put("active", false);
        update.put("endedAt", FieldValue.serverTimestamp());
        DocumentReference session = Db.col(Db.SESSIONS).document(sessionId);
        Db.fs().batch()
                .update(session, update)
                .delete(session.collection(Db.SECRET).document(Db.TOKEN_DOC))
                .commit()
                .addOnSuccessListener(x -> {
                    startActivity(new Intent(this, SessionReportActivity.class)
                            .putExtra(EXTRA_SESSION_ID, sessionId)
                            .putExtra(EXTRA_COURSE_ID, courseId));
                    finish();
                })
                .addOnFailureListener(e -> {
                    begin();
                    Ui.snack(qr, Ui.explain(this, e));
                });
    }

    private void abort(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.live_cant_start)
                .setMessage(message)
                .setPositiveButton(R.string.action_ok, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
