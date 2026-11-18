package com.example.smartattendance.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;

/** Collection names and document-id conventions shared by the app and firestore.rules. */
public final class Db {
    public static final String USERS = "users";
    public static final String DEVICES = "devices";
    public static final String COURSES = "courses";
    public static final String ENROLLMENTS = "enrollments";
    public static final String SESSIONS = "sessions";
    public static final String ATTENDANCE = "attendance";
    public static final String SECRET = "secret";
    public static final String TOKEN_DOC = "token";

    private Db() {}

    public static FirebaseFirestore fs() {
        return FirebaseFirestore.getInstance();
    }

    public static CollectionReference col(String name) {
        return fs().collection(name);
    }

    public static String uid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    public static String enrollmentId(String courseId, String studentId) {
        return courseId + "_" + studentId;
    }

    public static String attendanceId(String sessionId, String studentId) {
        return sessionId + "_" + studentId;
    }
}
