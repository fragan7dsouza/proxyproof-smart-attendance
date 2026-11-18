# How ProxyProof works

## App structure

```
 ┌───────────────────────── Android app (Java + XML) ─────────────────────────┐
 │  MainActivity (router: loads profile, enforces device binding)             │
 │   ├─ auth/      LoginActivity, RegisterActivity                            │
 │   ├─ student/   StudentHomeActivity, StudentCourseActivity,                │
 │   │             MarkAttendanceActivity  ← proof chain (5 checks)           │
 │   ├─ faculty/   FacultyHomeActivity, CourseActivity,                       │
 │   │             LiveSessionActivity     ← rotating QR, live count          │
 │   │             SessionReportActivity                                      │
 │   ├─ data/      POJOs (Profile, Course, Enrollment, ClassSession,          │
 │   │             AttendanceRecord) + Db (collection names, id rules)        │
 │   └─ util/      QrPayload, Codes, Stats, Ui, RowAdapter                    │
 └──────────────┬────────────────────────────────────────────┬────────────────┘
                │ Firebase Auth (email/password)             │ Cloud Firestore
                ▼                                            ▼
        ┌───────────────┐          ┌──────────────────────────────────────────┐
        │ Firebase Auth │          │ firestore.rules: re-checks every write   │
        └───────────────┘          │ on Google's servers (the real gatekeeper)│
                                   └──────────────────────────────────────────┘
```

## Firestore data model

```
config/faculty              code                      ← set by the college admin; unreadable by apps
users/{uid}                 name, email, role (student|faculty), rollNo, department, deviceId
devices/{deviceId}          uid                       ← one phone, one student
courses/{courseId}          name, code, joinCode, facultyId, facultyName
enrollments/{courseId_uid}  courseId, studentId, studentName, rollNo, courseName, …
sessions/{sessionId}        courseId, facultyId, active, startedAt, endedAt, lat, lng, radiusM, mPerDegLng
  └─ secret/token           token, prevToken, tokenAt ← faculty-only; the live QR value
attendance/{sessionId_uid}  sessionId, courseId, facultyId, studentId, deviceId, token, lat, lng, distanceM, markedAt
```

The fixed document ids (`courseId_uid`, `sessionId_uid`) make duplicates impossible: a second write to the
same id would be an *update*, and the rules forbid updating attendance.

## How a mark gets accepted

1. **Faculty** taps *Start class*. The app saves the faculty's GPS position and the chosen radius in `sessions/{id}`.
2. Every 15 seconds the faculty's phone writes a new random 16-character token to `sessions/{id}/secret/token`
   (keeping the previous one) and shows `PROXYPROOF1|sessionId|token` as a QR code.
3. **Student** taps *Mark attendance* and the app runs five checks in order:
   1. this phone's id matches the one bound to the account
   2. `BiometricPrompt` (fingerprint, face or screen lock)
   3. a fresh GPS fix that isn't from a mock-location app
   4. scan the QR (identity and location are checked first, so the token is used within a second or two)
   5. write `attendance/{sessionId_uid}`
4. **Firestore rules** check everything again on the server: the token matches (current one for 30 s, previous one
   for 10 s after rotation), the class is live, the student is enrolled, the phone is bound both ways, the distance
   is within the radius, and the timestamp is the server's. The distance is recomputed from the student's raw
   coordinates using `mPerDegLng`, because the rules language has no `cos()`. If any check fails, the write is rejected.

## Libraries

| Purpose | Library |
|---|---|
| Login | `firebase-auth` |
| Database | `firebase-firestore` (Firebase BoM 35.2.0) |
| GPS | `play-services-location` (Fused Location Provider) |
| Fingerprint / screen lock | `androidx.biometric` |
| QR generation and scanning | `zxing-android-embedded` 4.3.0 |
| UI | Material Components 3, RecyclerView, Public Sans font |
