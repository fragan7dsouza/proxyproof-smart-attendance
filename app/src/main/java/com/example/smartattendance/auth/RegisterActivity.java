package com.example.smartattendance.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.smartattendance.MainActivity;
import com.example.smartattendance.R;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Profile;
import com.example.smartattendance.util.Ui;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.WriteBatch;

import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private MaterialButtonToggleGroup roleGroup;
    private TextInputLayout nameLayout, emailLayout, passwordLayout, rollLayout, deptLayout, facultyCodeLayout;
    private TextView roleHint, notice;
    private Button create;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        roleGroup = findViewById(R.id.group_role);
        nameLayout = findViewById(R.id.layout_name);
        emailLayout = findViewById(R.id.layout_email);
        passwordLayout = findViewById(R.id.layout_password);
        rollLayout = findViewById(R.id.layout_roll);
        deptLayout = findViewById(R.id.layout_department);
        facultyCodeLayout = findViewById(R.id.layout_faculty_code);
        roleHint = findViewById(R.id.text_role_hint);
        notice = findViewById(R.id.text_notice);
        create = findViewById(R.id.button_create);

        roleGroup.addOnButtonCheckedListener((g, id, checked) -> { if (checked) updateRole(); });
        updateRole();
        create.setOnClickListener(v -> register());
    }

    private boolean isStudent() {
        return roleGroup.getCheckedButtonId() == R.id.button_student;
    }

    private void updateRole() {
        rollLayout.setVisibility(isStudent() ? View.VISIBLE : View.GONE);
        facultyCodeLayout.setVisibility(isStudent() ? View.GONE : View.VISIBLE);
        roleHint.setText(isStudent() ? R.string.register_student_hint : R.string.register_faculty_hint);
    }

    private void register() {
        String name = LoginActivity.text(nameLayout);
        String email = LoginActivity.text(emailLayout);
        String password = LoginActivity.text(passwordLayout);
        String roll = LoginActivity.text(rollLayout);
        String dept = LoginActivity.text(deptLayout);
        String facultyCode = LoginActivity.text(facultyCodeLayout);
        boolean student = isStudent();

        nameLayout.setError(name.isEmpty() ? getString(R.string.error_name_required) : null);
        emailLayout.setError(Patterns.EMAIL_ADDRESS.matcher(email).matches() ? null : getString(R.string.error_email_invalid));
        passwordLayout.setError(password.length() >= 6 ? null : getString(R.string.error_password_short));
        rollLayout.setError(student && roll.isEmpty() ? getString(R.string.error_roll_required) : null);
        facultyCodeLayout.setError(!student && facultyCode.isEmpty() ? getString(R.string.error_faculty_code_required) : null);
        if (nameLayout.getError() != null || emailLayout.getError() != null || passwordLayout.getError() != null
                || rollLayout.getError() != null || facultyCodeLayout.getError() != null) return;

        setBusy(true);
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> saveProfile(result.getUser(), name, email, roll, dept, facultyCode, student))
                .addOnFailureListener(e -> {
                    setBusy(false);
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        emailLayout.setError(getString(R.string.error_email_taken));
                    } else if (e instanceof FirebaseAuthWeakPasswordException) {
                        passwordLayout.setError(getString(R.string.error_password_short));
                    } else {
                        showNotice(Ui.explain(this, e));
                    }
                });
    }

    /** Profile and device binding are written together, so a student never exists without a phone. */
    private void saveProfile(FirebaseUser user, String name, String email, String roll, String dept,
                             String facultyCode, boolean student) {
        String uid = user.getUid();
        String deviceId = student ? Ui.deviceId(this) : null;

        Map<String, Object> profile = new HashMap<>();
        profile.put("name", name);
        profile.put("email", email);
        profile.put("role", student ? Profile.STUDENT : Profile.FACULTY);
        profile.put("rollNo", student ? roll : null);
        profile.put("department", dept);
        profile.put("deviceId", deviceId);
        if (!student) profile.put("facultyCode", facultyCode);
        profile.put("createdAt", FieldValue.serverTimestamp());

        WriteBatch batch = Db.fs().batch();
        if (student) {
            Map<String, Object> device = new HashMap<>();
            device.put("uid", uid);
            device.put("boundAt", FieldValue.serverTimestamp());
            batch.set(Db.col(Db.DEVICES).document(deviceId), device);
        }
        batch.set(Db.col(Db.USERS).document(uid), profile);
        batch.commit()
                .addOnSuccessListener(x -> {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                })
                .addOnFailureListener(e -> {
                    // Don't leave a half-made account behind: remove the login we just created.
                    user.delete();
                    FirebaseAuth.getInstance().signOut();
                    setBusy(false);
                    boolean denied = e instanceof FirebaseFirestoreException
                            && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED;
                    String reason = !denied ? Ui.explain(this, e)
                            : getString(student ? R.string.error_device_taken : R.string.error_faculty_code_wrong);
                    showNotice(reason);
                });
    }

    private void setBusy(boolean busy) {
        create.setEnabled(!busy);
        create.setText(busy ? R.string.creating_account : R.string.action_create_account);
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
    }
}
