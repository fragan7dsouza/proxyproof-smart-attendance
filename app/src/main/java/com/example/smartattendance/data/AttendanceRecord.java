package com.example.smartattendance.data;

import com.google.firebase.Timestamp;

/** attendance/{sessionId_studentId}. The fixed id makes a second mark for the same class impossible. */
public class AttendanceRecord {
    public String sessionId;
    public String courseId;
    public String facultyId;
    public String studentId;
    public String studentName;
    public String rollNo;
    public String deviceId;
    public double distanceM;
    public Timestamp markedAt;

    public AttendanceRecord() {}
}
