package com.example.smartattendance.data;

import com.google.firebase.firestore.DocumentId;

/** users/{uid} */
public class Profile {
    public static final String STUDENT = "student";
    public static final String FACULTY = "faculty";

    @DocumentId public String uid;
    public String name;
    public String email;
    public String role;
    public String rollNo;
    public String department;
    /** ANDROID_ID of the one phone this student may mark attendance from. Null until bound. */
    public String deviceId;

    public Profile() {}

    public boolean isStudent() {
        return STUDENT.equals(role);
    }

    public String firstName() {
        if (name == null) return "";
        // Skip titles so "Dr. Meera Kamath" greets as "Meera", not "Dr."
        String trimmed = name.trim().replaceFirst("(?i)^(dr|prof|mr|mrs|ms)\\.?\\s+(?=\\S)", "");
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
