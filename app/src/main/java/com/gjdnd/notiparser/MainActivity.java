package com.gjdnd.notiparser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(AlarmParser.PREFS, Context.MODE_PRIVATE);
        tvLog = findViewById(R.id.tv_log);

        EditText etToken = findViewById(R.id.et_token);
        EditText etGistId = findViewById(R.id.et_gist_id);
        EditText etTrigger = findViewById(R.id.et_trigger);
        Switch swKakao = findViewById(R.id.sw_kakao);

        etToken.setText(prefs.getString("gist_token", ""));
        etGistId.setText(prefs.getString("gist_id", ""));
        etTrigger.setText(prefs.getString("trigger", "체결"));
        swKakao.setChecked(prefs.getBoolean("include_kakao", false));

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(v -> {
            String trigger = etTrigger.getText().toString().trim();
            prefs.edit()
                    .putString("gist_token", etToken.getText().toString().trim())
                    .putString("gist_id", etGistId.getText().toString().trim())
                    .putString("trigger", trigger.isEmpty() ? "체결" : trigger)
                    .putBoolean("include_kakao", swKakao.isChecked())
                    .apply();
            Toast.makeText(this, "설정 저장 완료", Toast.LENGTH_SHORT).show();
            // 설정 직후 규칙도 갱신 + 못 보낸 큐 재전송
            GistManager.refreshRules(this, this::refreshLogOnUi);
            GistManager.flushQueue(this);
        });

        ((Button) findViewById(R.id.btn_permission)).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        ((Button) findViewById(R.id.btn_refresh_rules)).setOnClickListener(v ->
                GistManager.refreshRules(this, this::refreshLogOnUi));

        ((Button) findViewById(R.id.btn_test)).setOnClickListener(v -> runTest(false));
        ((Button) findViewById(R.id.btn_test_send)).setOnClickListener(v -> runTest(true));

        ((Button) findViewById(R.id.btn_show_raw)).setOnClickListener(v -> showRawDialog());

        // 앱 열 때마다 규칙 갱신 + 미전송 큐 재시도
        if (!prefs.getString("gist_token", "").isEmpty()) {
            GistManager.refreshRules(this, this::refreshLogOnUi);
            GistManager.flushQueue(this);
        }
        refreshLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    /** 테스트 파싱: 입력 텍스트를 실제 알림처럼 처리. send=false면 큐 적재 직전까지만. */
    private void runTest(boolean send) {
        EditText etTest = findViewById(R.id.et_test);
        String text = etTest.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "테스트할 텍스트를 입력하세요", Toast.LENGTH_SHORT).show();
            return;
        }
        if (send) {
            AlarmParser.handle(this, "manual.test", text);
            Toast.makeText(this, "처리 완료 — 로그 확인", Toast.LENGTH_SHORT).show();
        } else {
            // 전송 없이 파싱 결과만 보여줌
            String result = AlarmParser.dryRun(this, text);
            new AlertDialog.Builder(this)
                    .setTitle("파싱 결과")
                    .setMessage(result)
                    .setPositiveButton("확인", null)
                    .show();
        }
        refreshLog();
    }

    private void showRawDialog() {
        JSONArray raw = AppLog.getRaw(this);
        StringBuilder sb = new StringBuilder();
        for (int i = raw.length() - 1; i >= 0; i--) {
            try {
                JSONObject o = new JSONObject(raw.getString(i));
                sb.append("[").append(o.optString("time")).append("] ")
                        .append(o.optString("pkg")).append("\n")
                        .append(o.optString("text")).append("\n\n");
            } catch (Exception ignore) {}
        }
        if (sb.length() == 0) sb.append("수집된 알림이 없습니다.");
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextIsSelectable(true);
        tv.setPadding(40, 20, 40, 20);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("수집된 알림 원본")
                .setView(sv)
                .setPositiveButton("닫기", null)
                .show();
    }

    private void refreshLog() {
        JSONArray events = AppLog.getEvents(this);
        StringBuilder sb = new StringBuilder();
        for (int i = events.length() - 1; i >= 0; i--) {
            sb.append(events.optString(i)).append('\n');
        }
        tvLog.setText(sb.length() == 0 ? "로그 없음" : sb.toString());
    }

    private void refreshLogOnUi() {
        runOnUiThread(this::refreshLog);
    }
}
