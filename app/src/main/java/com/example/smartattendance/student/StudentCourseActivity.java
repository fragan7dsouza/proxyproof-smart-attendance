package com.example.smartattendance.student;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.ClassSession;
import com.example.smartattendance.data.Course;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.util.RowAdapter;
import com.example.smartattendance.util.Stats;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One course from the student's side: percentage, the 75% standing, and every class held. */
public class StudentCourseActivity extends AppCompatActivity {

    public static final String EXTRA_COURSE_ID = "courseId";

    private String courseId;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        setContentView(R.layout.activity_student_course);
        courseId = getIntent().getStringExtra(EXTRA_COURSE_ID);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new RowAdapter();
        RecyclerView list = findViewById(R.id.list_sessions);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        load();
    }

    private void load() {
        String uid = ProxyProofApp.profile.uid;
        Tasks.whenAllSuccess(
                Db.col(Db.COURSES).document(courseId).get(),
                Db.col(Db.SESSIONS).whereEqualTo("courseId", courseId).get(),
                Db.col(Db.ATTENDANCE).whereEqualTo("studentId", uid).whereEqualTo("courseId", courseId).get()
        ).addOnSuccessListener(results -> {
            Course course = ((DocumentSnapshot) results.get(0)).toObject(Course.class);
            List<ClassSession> sessions = ((QuerySnapshot) results.get(1)).toObjects(ClassSession.class);
            Map<String, AttendanceRecord> mine = new HashMap<>();
            for (AttendanceRecord r : ((QuerySnapshot) results.get(2)).toObjects(AttendanceRecord.class)) {
                mine.put(r.sessionId, r);
            }
            show(course, sessions, mine);
        }).addOnFailureListener(e -> Ui.snack(findViewById(R.id.list_sessions), Ui.explain(this, e)));
    }

    private void show(Course course, List<ClassSession> sessions, Map<String, AttendanceRecord> mine) {
        if (course != null) {
            ((TextView) findViewById(R.id.text_course_name)).setText(course.name);
            ((TextView) findViewById(R.id.text_course_meta)).setText(
                    getString(R.string.course_row_subtitle, course.code, Ui.nullToEmpty(course.facultyName)));
        }
        sessions.sort((a, b) -> compare(b, a)); // newest first

        int attended = 0, total = 0;
        List<RowAdapter.Row> rows = new ArrayList<>();
        int present = ContextCompat.getColor(this, R.color.present);
        int absent = ContextCompat.getColor(this, R.color.absent);
        int muted = ContextCompat.getColor(this, R.color.muted);
        for (ClassSession s : sessions) {
            AttendanceRecord r = mine.get(s.id);
            RowAdapter.Row row;
            if (r != null) {
                row = new RowAdapter.Row(s.id, Ui.dateTime(s.startedAt),
                        getString(R.string.history_marked, Ui.time(r.markedAt), Math.round(r.distanceM)))
                        .trailing(getString(R.string.status_present), present);
                attended++;
                total++;
            } else if (s.active) {
                row = new RowAdapter.Row(s.id, Ui.dateTime(s.startedAt), getString(R.string.history_live))
                        .trailing(getString(R.string.status_live), muted);
            } else {
                row = new RowAdapter.Row(s.id, Ui.dateTime(s.startedAt), null)
                        .trailing(getString(R.string.status_absent), absent);
                total++;
            }
            rows.add(row);
        }
        adapter.submit(rows);

        TextView percent = findViewById(R.id.text_percent);
        percent.setText(Ui.percentText(this, attended, total));
        percent.setTextColor(Ui.percentColor(this, attended, total));
        ((TextView) findViewById(R.id.text_count)).setText(
                getResources().getQuantityString(R.plurals.classes_attended, total, attended, total));
        ((TextView) findViewById(R.id.text_standing)).setText(standing(this, attended, total));
        findViewById(R.id.text_empty).setVisibility(sessions.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private static int compare(ClassSession a, ClassSession b) {
        if (a.startedAt == null || b.startedAt == null) return a.startedAt == null ? 1 : -1;
        return a.startedAt.compareTo(b.startedAt);
    }

    /** The sentence students actually care about: how close they are to the 75% line. */
    static String standing(Context c, int attended, int total) {
        if (total == 0) return c.getString(R.string.standing_none);
        if (Stats.isSafe(attended, total)) {
            int spare = Stats.classesSpare(attended, total);
            return spare == 0
                    ? c.getString(R.string.standing_on_line)
                    : c.getResources().getQuantityString(R.plurals.standing_spare, spare, spare);
        }
        int needed = Stats.classesNeeded(attended, total);
        return c.getResources().getQuantityString(R.plurals.standing_needed, needed, needed);
    }
}
