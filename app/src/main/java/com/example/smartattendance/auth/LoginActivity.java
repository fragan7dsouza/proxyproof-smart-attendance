package com.example.smartattendance.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.smartattendance.MainActivity;
import com.example.smartattendance.R;
import com.example.smartattendance.util.Ui;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

public class LoginActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGE = "message";

    private TextInputLayout emailLayout;
    private TextInputLayout passwordLayout;
    private Button signIn;
    private TextView notice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        emailLayout = findViewById(R.id.layout_email);
        passwordLayout = findViewById(R.id.layout_password);
        signIn = findViewById(R.id.button_sign_in);
        notice = findViewById(R.id.text_notice);

        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        if (!TextUtils.isEmpty(message)) showNotice(message);

        signIn.setOnClickListener(v -> signIn());
        findViewById(R.id.button_register).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void signIn() {
        String email = text(emailLayout);
        String password = text(passwordLayout);
        emailLayout.setError(email.isEmpty() ? getString(R.string.error_email_required) : null);
        passwordLayout.setError(password.isEmpty() ? getString(R.string.error_password_required) : null);
        if (email.isEmpty() || password.isEmpty()) return;

        setBusy(true);
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(r -> {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    boolean badLogin = e instanceof FirebaseAuthInvalidCredentialsException
                            || e instanceof FirebaseAuthInvalidUserException;
                    showNotice(badLogin ? getString(R.string.error_bad_login) : Ui.explain(this, e));
                });
    }

    private void setBusy(boolean busy) {
        signIn.setEnabled(!busy);
        signIn.setText(busy ? R.string.signing_in : R.string.action_sign_in);
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
    }

    static String text(TextInputLayout layout) {
        return layout.getEditText() == null ? "" : layout.getEditText().getText().toString().trim();
    }
}
