package com.example.smartattendance;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.smartattendance.auth.LoginActivity;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Profile;
import com.example.smartattendance.faculty.FacultyHomeActivity;
import com.example.smartattendance.student.StudentHomeActivity;
import com.example.smartattendance.util.Ui;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.WriteBatch;

import java.util.HashMap;
import java.util.Map;

/**
 * Launch screen and router. Loads the signed-in user's profile, enforces the
 * one-student-one-phone rule, then opens the student or faculty home.
 */
public class MainActivity extends AppCompatActivity {

    private View progress;
    private View retry;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        progress = findViewById(R.id.progress);
        retry = findViewById(R.id.button_retry);
        status = findViewById(R.id.text_status);
        retry.setOnClickListener(v -> route());
        route();
    }

    private void route() {
        String uid = Db.uid();
        if (uid == null) {
            openLogin(null);
            return;
        }
        showLoading();
        Db.col(Db.USERS).document(uid).get()
                .addOnSuccessListener(snap -> {
                    Profile profile = snap.toObject(Profile.class);
                    if (profile == null) {
                        signOutTo(getString(R.string.error_profile_missing));
                        return;
                    }
                    if (!profile.isStudent()) {
                        open(profile, FacultyHomeActivity.class);
                        return;
                    }
                    String thisPhone = Ui.deviceId(this);
                    if (thisPhone.equals(profile.deviceId)) {
                        open(profile, StudentHomeActivity.class);
                    } else if (profile.deviceId == null) {
                        bindThisPhone(profile, thisPhone);
                    } else {
                        signOutTo(getString(R.string.error_other_device));
                    }
                })
                .addOnFailureListener(e -> showError(Ui.explain(this, e)));
    }

    /** Runs after faculty reset a student's binding: the next phone they sign in on becomes theirs. */
    private void bindThisPhone(Profile profile, String thisPhone) {
        Map<String, Object> device = new HashMap<>();
        device.put("uid", profile.uid);
        device.put("boundAt", FieldValue.serverTimestamp());
        WriteBatch batch = Db.fs().batch();
        batch.set(Db.col(Db.DEVICES).document(thisPhone), device);
        batch.update(Db.col(Db.USERS).document(profile.uid), "deviceId", thisPhone);
        batch.commit()
                .addOnSuccessListener(x -> {
                    profile.deviceId = thisPhone;
                    open(profile, StudentHomeActivity.class);
                })
                .addOnFailureListener(e -> signOutTo(getString(R.string.error_device_taken)));
    }

    private void open(Profile profile, Class<?> screen) {
        ProxyProofApp.profile = profile;
        startActivity(new Intent(this, screen));
        finish();
    }

    private void signOutTo(String message) {
        FirebaseAuth.getInstance().signOut();
        ProxyProofApp.profile = null;
        openLogin(message);
    }

    private void openLogin(String message) {
        startActivity(new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_MESSAGE, message));
        finish();
    }

    private void showLoading() {
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        status.setText(R.string.loading_profile);
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        status.setText(message);
    }
}
