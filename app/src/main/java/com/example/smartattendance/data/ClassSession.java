package com.example.smartattendance.data;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;

/**
 * sessions/{sessionId}: one class meeting. The rotating QR token lives in
 * sessions/{sessionId}/secret/token, which only the faculty can read.
 */
public class ClassSession {
    @DocumentId public String id;
    public String courseId;
    public String courseName;
    public String courseCode;
    public String facultyId;
    public boolean active;
    public Timestamp startedAt;
    public Timestamp endedAt;
    /** Where the faculty stood when the class started; the centre of the geofence. */
    public double lat;
    public double lng;
    public long radiusM;

    public ClassSession() {}
}
