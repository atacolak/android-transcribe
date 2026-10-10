package dev.notune.transcribe;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VocabActivity extends AppCompatActivity {
    private static final String TAG = "VocabActivity";

    private LinearLayout list;
    private TextView empty;
    private MaterialSwitch fuzzyGlobal;
    private MaterialSwitch diag;
    private JSONObject store;

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load c++_shared", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private native String loadVocabNative(VocabActivity activity);
    private native boolean saveVocabNative(VocabActivity activity, String json);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vocab);
        MaterialToolbar bar = findViewById(R.id.vocab_toolbar);
        bar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.vocab_list);
        empty = findViewById(R.id.vocab_empty);
        fuzzyGlobal = findViewById(R.id.switch_vocab_fuzzy);
        diag = findViewById(R.id.switch_vocab_diag);
        findViewById(R.id.btn_vocab_add).setOnClickListener(v -> editEntry(-1));

        loadStore();
        fuzzyGlobal.setOnCheckedChangeListener((b, on) -> {
            putBool("fuzzy_enabled", on);
            persist();
        });
        diag.setOnCheckedChangeListener((b, on) -> {
            putBool("diag_raw", on);
            persist();
        });
        render();
    }

    private void loadStore() {
        try {
            String json = loadVocabNative(this);
            store = (json == null || json.isEmpty()) ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "load vocab", e);
            store = new JSONObject();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "load vocab native", e);
            store = new JSONObject();
        }
        fuzzyGlobal.setOnCheckedChangeListener(null);
        diag.setOnCheckedChangeListener(null);
        fuzzyGlobal.setChecked(store.optBoolean("fuzzy_enabled", false));
        diag.setChecked(store.optBoolean("diag_raw", false));
    }

    private JSONArray entries() {
        JSONArray a = store.optJSONArray("entries");
        if (a == null) {
            a = new JSONArray();
            try { store.put("entries", a); } catch (Exception ignored) {}
        }
        return a;
    }

    private void putBool(String key, boolean v) {
        try { store.put(key, v); } catch (Exception ignored) {}
    }

    private void persist() {
        try {
            if (!saveVocabNative(this, store.toString())) {
                Snackbar.make(list, "Save failed", Snackbar.LENGTH_SHORT).show();
            }
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "save vocab native", e);
        }
    }

    private void render() {
        list.removeAllViews();
        JSONArray arr = entries();
        empty.setVisibility(arr.length() == 0 ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            View row = inf.inflate(R.layout.item_vocab_entry, list, false);
            TextView title = row.findViewById(R.id.vocab_item_title);
            TextView sub = row.findViewById(R.id.vocab_item_sub);
            title.setText(e.optString("canonical"));
            List<String> aliases = new ArrayList<>();
            JSONArray al = e.optJSONArray("aliases");
            if (al != null) {
                for (int j = 0; j < al.length(); j++) aliases.add(al.optString(j));
            }
            String flags = (e.optBoolean("enabled", true) ? "" : "disabled · ")
                    + (e.optBoolean("fuzzy", false) ? "fuzzy" : "aliases only");
            String heard = aliases.isEmpty() ? flags : String.join(", ", aliases) + " · " + flags;
            sub.setText(heard);
            final int idx = i;
            row.setOnClickListener(v -> editEntry(idx));
            list.addView(row);
        }
    }

    private void editEntry(int index) {
        boolean adding = index < 0;
        JSONObject existing = adding ? new JSONObject() : entries().optJSONObject(index);
        if (existing == null) existing = new JSONObject();
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_vocab_entry, null);
        EditText canonical = form.findViewById(R.id.vocab_canonical);
        EditText aliases = form.findViewById(R.id.vocab_aliases);
        CheckBox enabled = form.findViewById(R.id.vocab_enabled);
        CheckBox fuzzy = form.findViewById(R.id.vocab_fuzzy);
        canonical.setText(existing.optString("canonical"));
        JSONArray al = existing.optJSONArray("aliases");
        if (al != null) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < al.length(); i++) parts.add(al.optString(i));
            aliases.setText(String.join(", ", parts));
        }
        enabled.setChecked(existing.optBoolean("enabled", true));
        fuzzy.setChecked(existing.optBoolean("fuzzy", false));

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(adding ? R.string.vocab_add : R.string.vocab_title)
                .setView(form)
                .setPositiveButton(R.string.vocab_save, (d, w) -> {
                    String can = canonical.getText().toString().trim();
                    if (can.isEmpty()) {
                        Snackbar.make(list, R.string.vocab_need_canonical, Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        JSONObject e = new JSONObject();
                        e.put("canonical", can);
                        JSONArray next = new JSONArray();
                        for (String part : aliases.getText().toString().split(",")) {
                            String t = part.trim();
                            if (!t.isEmpty()) next.put(t.toLowerCase(Locale.ROOT));
                        }
                        e.put("aliases", next);
                        e.put("enabled", enabled.isChecked());
                        e.put("fuzzy", fuzzy.isChecked());
                        JSONArray arr = entries();
                        if (adding) arr.put(e);
                        else arr.put(index, e);
                        persist();
                        render();
                    } catch (Exception ex) {
                        Log.e(TAG, "save entry", ex);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!adding) {
            b.setNeutralButton(R.string.vocab_delete, (d, w) -> {
                JSONArray arr = entries();
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != index) next.put(arr.opt(i));
                }
                try { store.put("entries", next); } catch (Exception ignored) {}
                persist();
                render();
            });
        }
        b.show();
    }
}
