package com.example.smartattendance.faculty;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.R;
import com.example.smartattendance.data.AttendanceRecord;
import com.example.smartattendance.data.ClassSession;
import com.example.smartattendance.data.Course;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Enrollment;
import com.example.smartattendance.util.RowAdapter;
import com.example.smartattendance.util.Stats;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A faculty member's view of one course: join code, start a class, past classes, students. */
public class CourseActivity extends AppCompatActivity {

    public static final String EXTRA_COURSE_ID = "courseId";
    private static final int[] RADIUS_OPTIONS = {25, 50, 100};

    private String courseId;
    private Course course;
    private List<Enrollment> students = new ArrayList<>();
    private List<ClassSession> sessions = new ArrayList<>();
    /** sessionId -> studentIds present */
    private final Map<String, Set<String>> present = new HashMap<>();
    private ClassSession live;

    private RowAdapter adapter;
    private MaterialButtonToggleGroup tabs;
    private Button startButton;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        setContentView(R.layout.activity_course);
        courseId = getIntent().getStringExtra(EXTRA_COURSE_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_export) exportRegister();
            return true;
        });

        startButton = findViewById(R.id.button_start);
        startButton.setOnClickListener(v -> startOrResume());
        findViewById(R.id.button_copy).setOnClickListener(v -> copyJoinCode());
        emptyText = findViewById(R.id.text_empty);

        tabs = findViewById(R.id.group_tabs);
        tabs.addOnButtonCheckedListener((g, id, checked) -> { if (checked) render(); });

        adapter = new RowAdapter()
                .onClick(this::openRow)
                .onLongClick(row -> { if (isStudentsTab()) confirmResetDevice(row); });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        Tasks.whenAllSuccess(
                Db.col(Db.COURSES).document(courseId).get(),
                Db.col(Db.ENROLLMENTS).whereEqualTo("courseId", courseId).get(),
                Db.col(Db.SESSIONS).whereEqualTo("courseId", courseId).get(),
                Db.col(Db.ATTENDANCE).whereEqualTo("courseId", courseId).whereEqualTo("facultyId", Db.uid()).get()
        ).addOnSuccessListener(results -> {
            course = ((DocumentSnapshot) results.get(0)).toObject(Course.class);
            if (course == null) {
                finish();
                return;
            }
            students = ((QuerySnapshot) results.get(1)).toObjects(Enrollment.class);
            students.sort((a, b) -> Ui.nullToEmpty(a.rollNo).compareToIgnoreCase(Ui.nullToEmpty(b.rollNo)));
            sessions = ((QuerySnapshot) results.get(2)).toObjects(ClassSession.class);
            sessions.sort((a, b) -> {
                if (a.startedAt == null || b.startedAt == null) return a.startedAt == null ? -1 : 1;
                return b.startedAt.compareTo(a.startedAt);
            });
            present.clear();
            for (AttendanceRecord r : ((QuerySnapshot) results.get(3)).toObjects(AttendanceRecord.class)) {
                present.computeIfAbsent(r.sessionId, k -> new HashSet<>()).add(r.studentId);
            }
            live = null;
            for (ClassSession s : sessions) if (s.active) live = s;
            showHeader();
            render();
        }).addOnFailureListener(e -> Ui.snack(startButton, Ui.explain(this, e)));
    }

    private void showHeader() {
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle(course.code);
        ((TextView) findViewById(R.id.text_course_name)).setText(course.name);
        ((TextView) findViewById(R.id.text_join_code)).setText(course.joinCode);
        startButton.setText(live != null ? R.string.action_resume_class : R.string.action_start_class);
        int held = heldCount();
        ((TextView) findViewById(R.id.text_course_meta)).setText(getString(R.string.course_meta,
                getResources().getQuantityString(R.plurals.students_count, students.size(), students.size()),
                getResources().getQuantityString(R.plurals.classes_held, held, held)));
    }

    private boolean isStudentsTab() {
        return tabs.getCheckedButtonId() == R.id.tab_students;
    }

    private int heldCount() {
        int n = 0;
        for (ClassSession s : sessions) if (!s.active) n++;
        return n;
    }

    private void render() {
        if (course == null) return;
        List<RowAdapter.Row> rows = new ArrayList<>();
        int muted = getColor(R.color.muted);
        if (isStudentsTab()) {
            int held = heldCount();
            for (Enrollment e : students) {
                int attended = 0;
                for (ClassSession s : sessions) {
                    if (!s.active && present.containsKey(s.id) && present.get(s.id).contains(e.studentId)) attended++;
                }
                rows.add(new RowAdapter.Row(e.studentId, e.studentName,
                        getString(R.string.student_row_subtitle, Ui.nullToEmpty(e.rollNo),
                                getResources().getQuantityString(R.plurals.attended_short, held, attended, held)))
                        .avatar(Ui.initials(e.studentName))
                        .trailing(Ui.percentText(this, attended, held), Ui.percentColor(this, attended, held)));
            }
            emptyText.setText(getString(R.string.course_no_students, course.joinCode));
        } else {
            for (ClassSession s : sessions) {
                int count = present.containsKey(s.id) ? present.get(s.id).size() : 0;
                String subtitle = s.active ? getString(R.string.session_live)
                        : getString(R.string.session_ended, Ui.time(s.endedAt), s.radiusM);
                rows.add(new RowAdapter.Row(s.id, Ui.dateTime(s.startedAt), subtitle)
                        .trailing(getString(R.string.present_of, count, students.size()),
                                s.active ? getColor(R.color.present) : muted));
            }
            emptyText.setText(R.string.course_no_classes);
        }
        emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submit(rows);
    }

    private void openRow(RowAdapter.Row row) {
        if (isStudentsTab()) {
            confirmResetDevice(row);
            return;
        }
        boolean isLive = live != null && live.id.equals(row.id);
        startActivity(new Intent(this, isLive ? LiveSessionActivity.class : SessionReportActivity.class)
                .putExtra(LiveSessionActivity.EXTRA_SESSION_ID, row.id)
                .putExtra(LiveSessionActivity.EXTRA_COURSE_ID, courseId));
    }

    private void startOrResume() {
        if (course == null) return;
        if (live != null) {
            startActivity(new Intent(this, LiveSessionActivity.class)
                    .putExtra(LiveSessionActivity.EXTRA_SESSION_ID, live.id)
                    .putExtra(LiveSessionActivity.EXTRA_COURSE_ID, courseId));
            return;
        }
        String[] labels = new String[RADIUS_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(R.string.radius_option, RADIUS_OPTIONS[i]);
        final int[] chosen = {1};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.radius_title)
                .setSingleChoiceItems(labels, chosen[0], (d, which) -> chosen[0] = which)
                .setPositiveButton(R.string.action_start_class, (d, w) ->
                        startActivity(new Intent(this, LiveSessionActivity.class)
                                .putExtra(LiveSessionActivity.EXTRA_COURSE_ID, courseId)
                                .putExtra(LiveSessionActivity.EXTRA_RADIUS, RADIUS_OPTIONS[chosen[0]])))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void copyJoinCode() {
        if (course == null) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.join_code_label), course.joinCode));
        Ui.snack(startButton, getString(R.string.copied_join_code, course.joinCode));
    }

    /** For a student who changed or lost their phone: clears the binding so their next sign-in re-binds. */
    private void confirmResetDevice(RowAdapter.Row row) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.reset_title, row.title))
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.action_reset_phone, (d, w) ->
                        Db.col(Db.USERS).document(row.id).get().continueWithTask(t -> {
                            String oldDevice = t.getResult().getString("deviceId");
                            WriteBatch batch = Db.fs().batch();
                            // resetCourseId lets the rules confirm this faculty teaches the student.
                            batch.update(Db.col(Db.USERS).document(row.id), "deviceId", null, "resetCourseId", courseId);
                            if (oldDevice != null) batch.delete(Db.col(Db.DEVICES).document(oldDevice));
                            return batch.commit();
                        }).addOnSuccessListener(x -> Ui.snack(startButton, getString(R.string.reset_done, row.title)))
                          .addOnFailureListener(e -> Ui.snack(startButton, Ui.explain(this, e))))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Writes a P/A register (one column per class) to a CSV and opens the share sheet. */
    private void exportRegister() {
        if (course == null) return;
        List<ClassSession> held = new ArrayList<>();
        for (int i = sessions.size() - 1; i >= 0; i--) if (!sessions.get(i).active) held.add(sessions.get(i)); // oldest first
        SimpleDateFormat header = new SimpleDateFormat("d MMM yyyy h:mm a", Locale.getDefault());

        StringBuilder csv = new StringBuilder("Roll no,Name");
        for (ClassSession s : held) csv.append(',').append(cell(s.startedAt == null ? "" : header.format(s.startedAt.toDate())));
        csv.append(",Attended,Held,Percentage\n");
        for (Enrollment e : students) {
            csv.append(cell(e.rollNo)).append(',').append(cell(e.studentName));
            int attended = 0;
            for (ClassSession s : held) {
                boolean p = present.containsKey(s.id) && present.get(s.id).contains(e.studentId);
                if (p) attended++;
                csv.append(',').append(p ? "P" : "A");
            }
            int pct = Stats.percent(attended, held.size());
            csv.append(',').append(attended).append(',').append(held.size()).append(',')
                    .append(pct < 0 ? "" : pct + "%").append('\n');
        }

        try {
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
            String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
            File file = new File(dir, course.code.replaceAll("[^A-Za-z0-9]", "") + "_register_" + stamp + ".csv");
            try (Writer w = new FileWriter(file)) {
                w.write(csv.toString());
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject, course.code, course.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.export_chooser)));
        } catch (IOException e) {
            Ui.snack(startButton, getString(R.string.export_failed, e.getMessage()));
        }
    }

    private static String cell(String value) {
        String v = value == null ? "" : value;
        // Stop spreadsheet apps from running a name like =HYPERLINK(...) as a formula.
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
