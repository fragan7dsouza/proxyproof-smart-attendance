package com.example.smartattendance.data;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;

/** courses/{courseId} */
public class Course {
    @DocumentId public String id;
    public String name;
    public String code;
    public String joinCode;
    public String facultyId;
    public String facultyName;
    public Timestamp createdAt;

    public Course() {}
}
