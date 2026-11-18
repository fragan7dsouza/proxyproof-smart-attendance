package com.example.smartattendance.faculty;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.ClassSession;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Enrollment;
import com.example.smartattendance.util.RowAdapter;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Who came to one class and who didn't. */
public class SessionReportActivity extends AppCompatActivity {

    private final List<RowAdapter.Row> presentRows = new ArrayList<>();
    private final List<RowAdapter.Row> absentRows = new ArrayList<>();
    private RowAdapter adapter;
    private MaterialButtonToggleGroup tabs;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        setContentView(R.layout.activity_session_report);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        emptyText = findViewById(R.id.text_empty);

        adapter = new RowAdapter();
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        tabs = findViewById(R.id.group_tabs);
        tabs.addOnButtonCheckedListener((g, id, checked) -> { if (checked) render(); });

        String sessionId = getIntent().getStringExtra(LiveSessionActivity.EXTRA_SESSION_ID);
        String courseId = getIntent().getStringExtra(LiveSessionActivity.EXTRA_COURSE_ID);
        Tasks.whenAllSuccess(
                Db.col(Db.SESSIONS).document(sessionId).get(),
                Db.col(Db.ENROLLMENTS).whereEqualTo("courseId", courseId).get(),
                Db.col(Db.ATTENDANCE).whereEqualTo("sessionId", sessionId).whereEqualTo("facultyId", Db.uid()).get()
        ).addOnSuccessListener(results -> show(
                ((DocumentSnapshot) results.get(0)).toObject(ClassSession.class),
                ((QuerySnapshot) results.get(1)).toObjects(Enrollment.class),
                ((QuerySnapshot) results.get(2)).toObjects(AttendanceRecord.class)
        )).addOnFailureListener(e -> Ui.snack(list, Ui.explain(this, e)));
    }

    private void show(ClassSession session, List<Enrollment> students, List<AttendanceRecord> records) {
        if (session == null) {
            finish();
            return;
        }
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle(session.courseCode);
        ((TextView) findViewById(R.id.text_title)).setText(Ui.dateTime(session.startedAt));
        ((TextView) findViewById(R.id.text_subtitle)).setText(
                getString(R.string.report_subtitle, session.courseName, session.radiusM));

        Map<String, AttendanceRecord> byStudent = new HashMap<>();
        for (AttendanceRecord r : records) byStudent.put(r.studentId, r);

        records.sort((a, b) -> {
            if (a.markedAt == null || b.markedAt == null) return 0;
            return a.markedAt.compareTo(b.markedAt);
        });
        int present = getColor(R.color.present);
        int absent = getColor(R.color.absent);
        for (AttendanceRecord r : records) {
            presentRows.add(new RowAdapter.Row(r.studentId, r.studentName,
                    getString(R.string.report_present_row, Ui.nullToEmpty(r.rollNo), Math.round(r.distanceM)))
                    .avatar(Ui.initials(r.studentName))
                    .trailing(Ui.time(r.markedAt), present));
        }
        students.sort((a, b) -> Ui.nullToEmpty(a.rollNo).compareToIgnoreCase(Ui.nullToEmpty(b.rollNo)));
        for (Enrollment e : students) {
            if (byStudent.containsKey(e.studentId)) continue;
            absentRows.add(new RowAdapter.Row(e.studentId, e.studentName, Ui.nullToEmpty(e.rollNo))
                    .avatar(Ui.initials(e.studentName))
                    .trailing(getString(R.string.status_absent), absent));
        }

        TextView count = findViewById(R.id.text_count);
        count.setText(getString(R.string.present_of, records.size(), students.size()));
        ((TextView) findViewById(R.id.text_count_caption)).setText(
                session.active ? getString(R.string.report_still_live) : getString(R.string.report_present_caption));
        ((android.widget.Button) findViewById(R.id.tab_present)).setText(getString(R.string.tab_present, presentRows.size()));
        ((android.widget.Button) findViewById(R.id.tab_absent)).setText(getString(R.string.tab_absent, absentRows.size()));
        render();
    }

    private void render() {
        boolean showPresent = tabs.getCheckedButtonId() == R.id.tab_present;
        List<RowAdapter.Row> rows = showPresent ? presentRows : absentRows;
        adapter.submit(rows);
        emptyText.setText(showPresent ? R.string.report_none_present : R.string.report_none_absent);
        emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
