package com.example.smartattendance.faculty;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.MainActivity;
import com.example.smartattendance.ProxyProofApp;
import com.example.smartattendance.R;
import com.example.smartattendance.data.Course;
import com.example.smartattendance.data.Db;
import com.example.smartattendance.data.Enrollment;
import com.example.smartattendance.data.Profile;
import com.example.smartattendance.util.Codes;
import com.example.smartattendance.util.RowAdapter;
import com.example.smartattendance.util.Ui;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FacultyHomeActivity extends AppCompatActivity {

    private Profile me;
    private RowAdapter adapter;
    private View empty, loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.ensureProfile(this)) return;
        me = ProxyProofApp.profile;
        setContentView(R.layout.activity_faculty_home);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.greeting, me.firstName()));
        toolbar.setSubtitle(me.department == null || me.department.isEmpty()
                ? getString(R.string.role_faculty) : me.department);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_sign_out) signOut();
            return true;
        });

        empty = findViewById(R.id.panel_empty);
        loading = findViewById(R.id.progress);
        adapter = new RowAdapter().onClick(row -> startActivity(new Intent(this, CourseActivity.class)
                .putExtra(CourseActivity.EXTRA_COURSE_ID, row.id)));
        RecyclerView list = findViewById(R.id.list_courses);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.button_new_course).setOnClickListener(v -> showCreateDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (me != null) load();
    }

    private void load() {
        Db.col(Db.COURSES).whereEqualTo("facultyId", me.uid).get()
                .addOnSuccessListener(snap -> {
                    List<Course> courses = snap.toObjects(Course.class);
                    courses.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                    loading.setVisibility(View.GONE);
                    empty.setVisibility(courses.isEmpty() ? View.VISIBLE : View.GONE);
                    findViewById(R.id.text_courses_heading).setVisibility(courses.isEmpty() ? View.GONE : View.VISIBLE);
                    if (courses.isEmpty()) {
                        adapter.submit(new ArrayList<>());
                        return;
                    }
                    countStudents(courses);
                })
                .addOnFailureListener(e -> Ui.snack(empty, Ui.explain(this, e)));
    }

    private void countStudents(List<Course> courses) {
        List<String> ids = new ArrayList<>();
        for (Course c : courses) ids.add(c.id);
        List<Task<QuerySnapshot>> tasks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 30) {
            tasks.add(Db.col(Db.ENROLLMENTS).whereIn("courseId", ids.subList(i, Math.min(i + 30, ids.size()))).get());
        }
        Tasks.whenAllSuccess(tasks).addOnSuccessListener(results -> {
            Map<String, Integer> counts = new HashMap<>();
            for (Object r : results) {
                for (Enrollment e : ((QuerySnapshot) r).toObjects(Enrollment.class)) {
                    counts.merge(e.courseId, 1, Integer::sum);
                }
            }
            int muted = getColor(R.color.muted);
            List<RowAdapter.Row> rows = new ArrayList<>();
            for (Course c : courses) {
                int n = counts.containsKey(c.id) ? counts.get(c.id) : 0;
                rows.add(new RowAdapter.Row(c.id, c.name, getString(R.string.faculty_course_subtitle, c.code, c.joinCode))
                        .trailing(getResources().getQuantityString(R.plurals.students_count, n, n), muted));
            }
            adapter.submit(rows);
        }).addOnFailureListener(e -> Ui.snack(empty, Ui.explain(this, e)));
    }

    private void showCreateDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_course, null);
        TextInputLayout nameLayout = view.findViewById(R.id.layout_course_name);
        TextInputLayout codeLayout = view.findViewById(R.id.layout_course_code);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_course_title)
                .setView(view)
                .setPositiveButton(R.string.action_create_course, null)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameLayout.getEditText().getText().toString().trim();
            String code = codeLayout.getEditText().getText().toString().trim().toUpperCase(Locale.ROOT);
            nameLayout.setError(name.isEmpty() ? getString(R.string.error_course_name) : null);
            codeLayout.setError(code.isEmpty() ? getString(R.string.error_course_code) : null);
            if (name.isEmpty() || code.isEmpty()) return;
            v.setEnabled(false);
            createCourse(name, code, dialog, v);
        });
    }

    /** Picks a join code nobody else is using, then saves the course. */
    private void createCourse(String name, String code, AlertDialog dialog, View button) {
        String joinCode = Codes.joinCode();
        Db.col(Db.COURSES).whereEqualTo("joinCode", joinCode).limit(1).get()
                .continueWithTask(t -> {
                    if (!t.getResult().isEmpty()) return Tasks.<String>forResult(null); // rare clash: caller retries
                    Map<String, Object> course = new HashMap<>();
                    course.put("name", name);
                    course.put("code", code);
                    course.put("joinCode", joinCode);
                    course.put("facultyId", me.uid);
                    course.put("facultyName", me.name);
                    course.put("createdAt", FieldValue.serverTimestamp());
                    DocumentReference ref = Db.col(Db.COURSES).document();
                    return ref.set(course).continueWith(x -> ref.getId());
                })
                .addOnSuccessListener(id -> {
                    if (id == null) {
                        createCourse(name, code, dialog, button);
                        return;
                    }
                    dialog.dismiss();
                    startActivity(new Intent(this, CourseActivity.class).putExtra(CourseActivity.EXTRA_COURSE_ID, id));
                })
                .addOnFailureListener(e -> {
                    button.setEnabled(true);
                    Ui.snack(empty, Ui.explain(this, e));
                });
    }

    private void signOut() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_out_title)
                .setMessage(R.string.sign_out_message_faculty)
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
