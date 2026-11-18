package com.example.smartattendance.student;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.MainActivity;
import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.ClassSession;
import com.example.smartattendance.data.Course;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Enrollment;
import com.example.smartattendance.data.Profile;
import com.example.smartattendance.util.RowAdapter;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class StudentHomeActivity extends AppCompatActivity {

    private Profile me;
    private RowAdapter adapter;
    private TextView overallPercent, overallCaption, overallStanding;
    private View summary, empty, loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        me = ProxyProofApp.profile;
        setContentView(R.layout.activity_student_home);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.greeting, me.firstName()));
        toolbar.setSubtitle(getString(R.string.student_subtitle, Ui.nullToEmpty(me.rollNo)));
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_join) showJoinDialog();
            else if (item.getItemId() == R.id.menu_sign_out) signOut();
            return true;
        });

        summary = findViewById(R.id.panel_summary);
        empty = findViewById(R.id.panel_empty);
        loading = findViewById(R.id.progress);
        overallPercent = findViewById(R.id.text_overall_percent);
        overallCaption = findViewById(R.id.text_overall_caption);
        overallStanding = findViewById(R.id.text_overall_standing);

        adapter = new RowAdapter().onClick(row -> startActivity(new Intent(this, StudentCourseActivity.class)
                .putExtra(StudentCourseActivity.EXTRA_COURSE_ID, row.id)));
        RecyclerView list = findViewById(R.id.list_courses);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.button_mark).setOnClickListener(v ->
                startActivity(new Intent(this, MarkAttendanceActivity.class)));
        findViewById(R.id.button_join_empty).setOnClickListener(v -> showJoinDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (me != null) load();
    }

    /** Enrolments, then every session of those courses and every record of mine, combined in memory. */
    private void load() {
        Db.col(Db.ENROLLMENTS).whereEqualTo("studentId", me.uid).get()
                .addOnSuccessListener(snap -> {
                    List<Enrollment> enrollments = snap.toObjects(Enrollment.class);
                    if (enrollments.isEmpty()) {
                        showEmpty();
                        return;
                    }
                    List<String> courseIds = new ArrayList<>();
                    for (Enrollment e : enrollments) courseIds.add(e.courseId);

                    List<Task<QuerySnapshot>> tasks = new ArrayList<>();
                    tasks.add(Db.col(Db.ATTENDANCE).whereEqualTo("studentId", me.uid).get());
                    // whereIn accepts up to 30 values per query.
                    for (int i = 0; i < courseIds.size(); i += 30) {
                        tasks.add(Db.col(Db.SESSIONS)
                                .whereIn("courseId", courseIds.subList(i, Math.min(i + 30, courseIds.size()))).get());
                    }
                    Tasks.whenAllSuccess(tasks)
                            .addOnSuccessListener(results -> show(enrollments, results))
                            .addOnFailureListener(e -> Ui.snack(summary, Ui.explain(this, e)));
                })
                .addOnFailureListener(e -> Ui.snack(summary, Ui.explain(this, e)));
    }

    private void show(List<Enrollment> enrollments, List<Object> results) {
        Set<String> attendedSessions = new HashSet<>();
        for (AttendanceRecord r : ((QuerySnapshot) results.get(0)).toObjects(AttendanceRecord.class)) {
            attendedSessions.add(r.sessionId);
        }
        Map<String, int[]> perCourse = new HashMap<>(); // courseId -> {attended, total}
        for (int i = 1; i < results.size(); i++) {
            for (ClassSession s : ((QuerySnapshot) results.get(i)).toObjects(ClassSession.class)) {
                boolean attended = attendedSessions.contains(s.id);
                // A class still running only counts once you've marked it; otherwise it isn't an absence yet.
                if (s.active && !attended) continue;
                int[] counts = perCourse.computeIfAbsent(s.courseId, k -> new int[2]);
                counts[1]++;
                if (attended) counts[0]++;
            }
        }

        int attendedAll = 0, totalAll = 0;
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Enrollment e : enrollments) {
            int[] c = perCourse.containsKey(e.courseId) ? perCourse.get(e.courseId) : new int[2];
            attendedAll += c[0];
            totalAll += c[1];
            rows.add(new RowAdapter.Row(e.courseId, e.courseName,
                    getString(R.string.course_row_subtitle, e.courseCode, Ui.nullToEmpty(e.facultyName)))
                    .trailing(Ui.percentText(this, c[0], c[1]), Ui.percentColor(this, c[0], c[1])));
        }
        adapter.submit(rows);

        loading.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        summary.setVisibility(View.VISIBLE);
        findViewById(R.id.text_courses_heading).setVisibility(View.VISIBLE);
        overallPercent.setText(Ui.percentText(this, attendedAll, totalAll));
        overallPercent.setTextColor(Ui.percentColor(this, attendedAll, totalAll));
        overallCaption.setText(getResources().getQuantityString(R.plurals.classes_attended, totalAll,
                attendedAll, totalAll));
        overallStanding.setText(StudentCourseActivity.standing(this, attendedAll, totalAll));
    }

    private void showEmpty() {
        loading.setVisibility(View.GONE);
        summary.setVisibility(View.GONE);
        findViewById(R.id.text_courses_heading).setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
        adapter.submit(new ArrayList<>());
    }

    private void showJoinDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_join, null);
        TextInputLayout codeLayout = view.findViewById(R.id.layout_code);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.join_title)
                .setMessage(R.string.join_message)
                .setView(view)
                .setPositiveButton(R.string.action_join, null)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = codeLayout.getEditText().getText().toString().trim().toUpperCase(Locale.ROOT);
            if (code.length() != 6) {
                codeLayout.setError(getString(R.string.error_join_length));
                return;
            }
            codeLayout.setError(null);
            v.setEnabled(false);
            join(code, dialog, codeLayout, v);
        });
    }

    private void join(String code, AlertDialog dialog, TextInputLayout codeLayout, View button) {
        Db.col(Db.COURSES).whereEqualTo("joinCode", code).limit(1).get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        button.setEnabled(true);
                        codeLayout.setError(getString(R.string.error_join_unknown));
                        return;
                    }
                    DocumentSnapshot doc = snap.getDocuments().get(0);
                    Course course = doc.toObject(Course.class);
                    Map<String, Object> e = new HashMap<>();
                    e.put("courseId", course.id);
                    e.put("courseName", course.name);
                    e.put("courseCode", course.code);
                    e.put("facultyName", course.facultyName);
                    e.put("studentId", me.uid);
                    e.put("studentName", me.name);
                    e.put("rollNo", me.rollNo);
                    e.put("joinedAt", FieldValue.serverTimestamp());
                    Db.col(Db.ENROLLMENTS).document(Db.enrollmentId(course.id, me.uid)).get()
                            .continueWithTask(t -> {
                                if (t.getResult().exists()) throw new IllegalStateException(getString(R.string.error_join_already, course.name));
                                return Db.col(Db.ENROLLMENTS).document(Db.enrollmentId(course.id, me.uid)).set(e);
                            })
                            .addOnSuccessListener(x -> {
                                dialog.dismiss();
                                Ui.snack(summary, getString(R.string.joined, course.name));
                                load();
                            })
                            .addOnFailureListener(err -> {
                                button.setEnabled(true);
                                codeLayout.setError(err instanceof IllegalStateException ? err.getMessage() : Ui.explain(this, err));
                            });
                })
                .addOnFailureListener(err -> {
                    button.setEnabled(true);
                    codeLayout.setError(Ui.explain(this, err));
                });
    }

    private void signOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_out_title)
                .setMessage(R.string.sign_out_message_student)
                .setPositiveButton(R.string.action_sign_out, (d, w) -> {
                    FirebaseAuth.getInstance().signOut();
                    ProxyProofApp.profile = null;
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
