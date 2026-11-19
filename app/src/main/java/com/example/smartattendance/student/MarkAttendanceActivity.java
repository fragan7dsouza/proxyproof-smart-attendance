package com.example.smartattendance.student;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.ClassSession;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Profile;
import com.example.smartattendance.util.QrPayload;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The student's proof chain. Each check must pass before the next one runs:
 * 1. this phone is the one bound to the account
 * 2. the person holding it unlocks with fingerprint or screen lock
 * 3. a real (non-mock) GPS fix is taken
 * 4. the live QR is scanned
 * 5. the record is written; Firestore rules re-check token freshness, enrolment and device
 *
 * Identity and location come before the scan so the QR is used within a second or two
 * of being read, well inside its 15-second life.
 */
public class MarkAttendanceActivity extends AppCompatActivity {

    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private Step device, identity, location, scan, record;
    private View resultPanel;
    private TextView resultTitle, resultBody;
    private Button action;

    private Profile me;
    private Location fix;

    private final ActivityResultLauncher<String> locationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) takeLocation();
                else fail(location, getString(R.string.step_location_denied), R.string.action_try_again, this::checkLocation);
            });

    private final ActivityResultLauncher<ScanOptions> scanner =
            registerForActivityResult(new ScanContract(), result -> onScanned(result.getContents()));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        me = ProxyProofApp.profile;
        setContentView(R.layout.activity_mark_attendance);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        device = new Step(findViewById(R.id.step_device), R.string.step_device, R.string.step_device_hint);
        identity = new Step(findViewById(R.id.step_identity), R.string.step_identity, R.string.step_identity_hint);
        location = new Step(findViewById(R.id.step_location), R.string.step_location, R.string.step_location_hint);
        scan = new Step(findViewById(R.id.step_scan), R.string.step_scan, R.string.step_scan_hint);
        record = new Step(findViewById(R.id.step_record), R.string.step_record, R.string.step_record_hint);
        resultPanel = findViewById(R.id.panel_result);
        resultTitle = findViewById(R.id.text_result_title);
        resultBody = findViewById(R.id.text_result_body);
        action = findViewById(R.id.button_action);

        checkDevice();
    }

    // 1. Device binding ---------------------------------------------------------------------

    private void checkDevice() {
        resetFrom(device);
        if (Ui.deviceId(this).equals(me.deviceId)) {
            device.done(getString(R.string.step_device_ok));
            checkIdentity();
        } else {
            fail(device, getString(R.string.error_other_device), 0, null);
        }
    }

    // 2. Biometric / screen lock -------------------------------------------------------------

    private void checkIdentity() {
        resetFrom(identity);
        identity.running();
        int can = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            fail(identity, getString(R.string.step_identity_none), R.string.action_try_again, this::checkIdentity);
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        identity.done(getString(R.string.step_identity_ok));
                        if (ProxyProofApp.demo) demoFindClass();
                        else checkLocation();
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence message) {
                        boolean cancelled = code == BiometricPrompt.ERROR_USER_CANCELED
                                || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || code == BiometricPrompt.ERROR_CANCELED;
                        fail(identity, cancelled ? getString(R.string.step_identity_cancelled) : message.toString(),
                                R.string.action_try_again, MarkAttendanceActivity.this::checkIdentity);
                    }
                });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle, me.name))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
    }

    // 3. Location ----------------------------------------------------------------------------

    private void checkLocation() {
        resetFrom(location);
        location.running();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            takeLocation();
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @SuppressWarnings("MissingPermission") // only called once ACCESS_FINE_LOCATION is granted
    private void takeLocation() {
        LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(loc -> {
                    if (isDestroyed()) return;
                    if (loc == null) {
                        fail(location, getString(R.string.step_location_none), R.string.action_try_again, this::checkLocation);
                    } else if (isMock(loc)) {
                        fail(location, getString(R.string.step_location_mock), R.string.action_try_again, this::checkLocation);
                    } else {
                        fix = loc;
                        location.done(getString(R.string.step_location_ok, Math.round(loc.getAccuracy())));
                        startScan();
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isDestroyed()) fail(location, getString(R.string.step_location_none), R.string.action_try_again, this::checkLocation);
                });
    }

    @SuppressWarnings("deprecation")
    static boolean isMock(Location loc) {
        return Build.VERSION.SDK_INT >= 31 ? loc.isMock() : loc.isFromMockProvider();
    }

    /**
     * Demo mode (local emulators only): a laptop has no real GPS or camera, so find the live class this
     * student is enrolled in, place them at the class and stand in for the scan. The record step and the
     * server's other checks (enrolment, phone binding, one mark per class, server time) still run.
     */
    private void demoFindClass() {
        resetFrom(location);
        location.running();
        Db.col(Db.ENROLLMENTS).whereEqualTo("studentId", me.uid).get(Source.SERVER).continueWithTask(enrolled -> {
            Set<String> courses = new HashSet<>();
            for (DocumentSnapshot d : enrolled.getResult()) courses.add(d.getString("courseId"));
            return Db.col(Db.SESSIONS).whereEqualTo("active", true).get(Source.SERVER)
                    .continueWith(live -> {
                        for (DocumentSnapshot d : live.getResult()) {
                            ClassSession s = d.toObject(ClassSession.class);
                            if (s != null && courses.contains(s.courseId)) return s;
                        }
                        return null;
                    });
        }).addOnCompleteListener(task -> {
            if (isDestroyed()) return;
            if (!task.isSuccessful()) {
                fail(location, Ui.explain(this, task.getException()), R.string.action_try_again, this::demoFindClass);
                return;
            }
            ClassSession session = task.getResult();
            if (session == null) {
                fail(location, getString(R.string.demo_no_live_class), R.string.action_try_again, this::demoFindClass);
                return;
            }
            fix = new Location("demo");
            fix.setLatitude(session.lat);
            fix.setLongitude(session.lng);
            location.done(getString(R.string.step_location_demo));
            scan.done(getString(R.string.step_scan_demo));
            recordAttendance(QrPayload.demo(session.id));
        });
    }

    // 4. Scan --------------------------------------------------------------------------------

    private void startScan() {
        resetFrom(scan);
        scan.running();
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false);
        scanner.launch(options);
    }

    private void onScanned(String contents) {
        if (contents == null) {
            fail(scan, getString(R.string.step_scan_cancelled), R.string.action_scan_again, this::startScan);
            return;
        }
        QrPayload payload = QrPayload.parse(contents);
        if (payload == null) {
            fail(scan, getString(R.string.step_scan_invalid), R.string.action_scan_again, this::startScan);
            return;
        }
        scan.done(getString(R.string.step_scan_ok));
        recordAttendance(payload);
    }

    // 5. Record ------------------------------------------------------------------------------

    private void recordAttendance(QrPayload payload) {
        resetFrom(record);
        record.running();
        Db.col(Db.SESSIONS).document(payload.sessionId).get(Source.SERVER).addOnCompleteListener(task -> {
            if (isDestroyed()) return;
            if (!task.isSuccessful()) {
                fail(record, Ui.explain(this, task.getException()), R.string.action_scan_again, this::startScan);
                return;
            }
            ClassSession session = task.getResult().toObject(ClassSession.class);
            if (session == null) {
                fail(record, getString(R.string.record_no_session), R.string.action_scan_again, this::startScan);
            } else if (!session.active) {
                fail(record, getString(R.string.record_closed), 0, null);
            } else {
                checkEnrolled(session, payload);
            }
        });
    }

    private void checkEnrolled(ClassSession session, QrPayload payload) {
        Db.col(Db.ENROLLMENTS).document(Db.enrollmentId(session.courseId, me.uid)).get(Source.SERVER)
                .addOnCompleteListener(task -> {
                    if (isDestroyed()) return;
                    if (!task.isSuccessful()) {
                        fail(record, Ui.explain(this, task.getException()), R.string.action_scan_again, this::startScan);
                    } else if (!task.getResult().exists()) {
                        fail(record, getString(R.string.record_not_enrolled, session.courseCode), 0, null);
                    } else {
                        checkNotMarked(session, payload);
                    }
                });
    }

    private void checkNotMarked(ClassSession session, QrPayload payload) {
        DocumentReference ref = Db.col(Db.ATTENDANCE).document(Db.attendanceId(session.id, me.uid));
        ref.get(Source.SERVER).addOnCompleteListener(task -> {
            if (isDestroyed()) return;
            if (!task.isSuccessful()) {
                fail(record, Ui.explain(this, task.getException()), R.string.action_scan_again, this::startScan);
                return;
            }
            AttendanceRecord existing = task.getResult().toObject(AttendanceRecord.class);
            if (existing != null) {
                String when = Ui.time(existing.markedAt);
                record.done(getString(R.string.record_already, when));
                showResult(true, getString(R.string.result_already_title),
                        getString(R.string.result_already_body, session.courseName, when));
                return;
            }
            float[] meters = new float[1];
            Location.distanceBetween(fix.getLatitude(), fix.getLongitude(), session.lat, session.lng, meters);
            long distance = Math.round(meters[0]);
            if (distance > session.radiusM) {
                fail(record, getString(R.string.record_too_far, distance, session.radiusM), R.string.action_try_again, this::checkLocation);
                return;
            }
            write(ref, session, payload, distance);
        });
    }

    private void write(DocumentReference ref, ClassSession session, QrPayload payload, long distance) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.id);
        data.put("courseId", session.courseId);
        data.put("facultyId", session.facultyId);
        data.put("studentId", me.uid);
        data.put("studentName", me.name);
        data.put("rollNo", me.rollNo);
        data.put("deviceId", me.deviceId);
        data.put("token", payload.token);
        data.put("distanceM", distance); // for display; the rules recompute it from lat/lng
        data.put("lat", fix.getLatitude());
        data.put("lng", fix.getLongitude());
        data.put("markedAt", FieldValue.serverTimestamp());
        ref.set(data).addOnCompleteListener(task -> {
            if (isDestroyed()) return;
            if (task.isSuccessful()) {
                record.done(getString(R.string.record_ok, distance));
                showResult(true, getString(R.string.result_ok_title),
                        getString(R.string.result_ok_body, session.courseName, Ui.time(Timestamp.now())));
                return;
            }
            Exception e = task.getException();
            boolean denied = e instanceof FirebaseFirestoreException
                    && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED;
            // Everything else was checked above, so a rules rejection here means the QR token expired.
            fail(record, denied ? getString(R.string.record_expired) : Ui.explain(this, e), R.string.action_scan_again, this::startScan);
        });
    }

    // UI -------------------------------------------------------------------------------------

    private void resetFrom(Step step) {
        Step[] all = {device, identity, location, scan, record};
        boolean reset = false;
        for (Step s : all) {
            if (s == step) reset = true;
            if (reset) s.pending();
        }
        resultPanel.setVisibility(View.GONE);
        action.setVisibility(View.GONE);
    }

    private void fail(Step step, String reason, int actionText, Runnable retry) {
        step.failed(reason);
        showResult(false, getString(R.string.result_fail_title), reason);
        if (retry != null) {
            action.setText(actionText);
            action.setOnClickListener(v -> retry.run());
        }
        action.setVisibility(retry != null ? View.VISIBLE : View.GONE);
    }

    private void showResult(boolean ok, String title, String body) {
        resultPanel.setBackgroundResource(ok ? R.drawable.bg_result_ok : R.drawable.bg_result_fail);
        resultTitle.setTextColor(ContextCompat.getColor(this, ok ? R.color.present : R.color.absent));
        resultTitle.setText(title);
        resultBody.setText(body);
        resultPanel.setVisibility(View.VISIBLE);
        if (ok) {
            action.setText(R.string.action_done);
            action.setOnClickListener(v -> finish());
            action.setVisibility(View.VISIBLE);
        }
    }

    /** One row of the checklist. */
    private final class Step {
        final ImageView icon;
        final View spinner;
        final TextView detail;
        final String hint;

        Step(View root, int title, int hint) {
            icon = root.findViewById(R.id.step_icon);
            spinner = root.findViewById(R.id.step_spinner);
            detail = root.findViewById(R.id.step_detail);
            ((TextView) root.findViewById(R.id.step_title)).setText(title);
            this.hint = getString(hint);
            pending();
        }

        void pending() { show(R.drawable.ic_radio_button_unchecked, R.color.rule, hint, R.color.muted); }

        void running() {
            icon.setVisibility(View.INVISIBLE);
            spinner.setVisibility(View.VISIBLE);
        }

        void done(String text) { show(R.drawable.ic_check, R.color.present, text, R.color.muted); }

        void failed(String text) { show(R.drawable.ic_error, R.color.absent, text, R.color.absent); }

        private void show(int iconRes, int iconColor, String text, int textColor) {
            spinner.setVisibility(View.GONE);
            icon.setVisibility(View.VISIBLE);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(MarkAttendanceActivity.this, iconColor)));
            detail.setText(text);
            detail.setTextColor(ContextCompat.getColor(MarkAttendanceActivity.this, textColor));
        }
    }
}
