package com.example.smartattendance;

import android.app.Application;

import com.example.smartattendance.data.Profile;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class ProxyProofApp extends Application {

    /** Host the Android emulator uses to reach the laptop running the Firebase Emulator Suite. */
    private static final String EMULATOR_HOST = "10.0.2.2";

    /** Profile of the signed-in user. Loaded by MainActivity before any other screen opens. */
    public static Profile profile;

    /** True when running against the local demo emulators (never against a real Firebase project). */
    public static boolean demo;

    @Override
    public void onCreate() {
        super.onCreate();
        String projectId = FirebaseApp.getInstance().getOptions().getProjectId();
        // A "demo-" project id never exists on Google's servers, so it means "use the local emulators".
        // A real google-services.json from the Firebase console skips this and talks to the cloud.
        demo = projectId != null && projectId.startsWith("demo-");
        if (demo) {
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099);
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, 8080);
        }
    }
}
