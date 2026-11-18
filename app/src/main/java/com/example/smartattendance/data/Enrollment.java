package com.example.smartattendance.data;

import com.google.firebase.Timestamp;

/** enrollments/{courseId_studentId}. Course and student names are copied in so lists need one query. */
public class Enrollment {
    public String courseId;
    public String courseName;
    public String courseCode;
    public String facultyName;
    public String studentId;
    public String studentName;
    public String rollNo;
    public Timestamp joinedAt;

    public Enrollment() {}
}
