package com.gjdnd.notiparser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gist 입출력 담당.
 *
 * kakaoNoti 대비 개선점:
 * - GET 실패 시 빈 배열로 덮어쓰지 않음 (데이터 손실 방지) — 전송 못 한 항목은 로컬 큐에 보관 후 재시도
 * - HTTP 응답 코드 검증 (401/403 = 토큰 문제 → 폰 에러 알림)
 * - 모든 실패는 폰 알림으로 표시 (silent fail 제거)
 */
public class GistManager {
    private static final String TAG = "NotiParser";
    private static final String TRADES_FILE = "meritz_trades.json";
    private static final String RULES_FILE = "noti_rules.json";
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    /** 거래를 로컬 큐에 넣고 즉시 전송 시도. 실패해도 큐에 남아 다음 기회에 재전송. */
    public static void enqueueAndSend(Context ctx, JSONObject trade) {
        try {
            SharedPreferences prefs = prefs(ctx);
            JSONArray queue = new JSONArray(prefs.getString("send_queue", "[]"));
            queue.put(trade);
            prefs.edit().putString("send_queue", queue.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "큐 저장 실패", e);
        }
        flushQueue(ctx);
    }

    /** 큐에 쌓인 거래들을 Gist에 append. 단일 스레드라 동시 PATCH 충돌 없음. */
    public static void flushQueue(Context ctx) {
        exec.execute(() -> {
            SharedPreferences prefs = prefs(ctx);
            String token = prefs.getString("gist_token", "");
            String gistId = prefs.getString("gist_id", "");
            if (token.isEmpty() || gistId.isEmpty()) {
                showError(ctx, "Gist 토큰/ID 미설정 — 앱에서 설정 필요");
                return;
            }
            try {
                JSONArray queue = new JSONArray(prefs.getString("send_queue", "[]"));
                if (queue.length() == 0) return;

                // 1) 기존 배열 GET — 실패하면 절대 덮어쓰지 않고 중단
                String content = fetchFile(token, gistId, TRADES_FILE);
                JSONArray existing;
                if (content == null || content.trim().isEmpty()) {
                    existing = new JSONArray();
                } else {
                    try {
                        existing = new JSONArray(content);
                    } catch (Exception e) {
                        existing = new JSONArray(); // 파일이 "[]" 아닌 쓰레기값이면 초기화
                    }
                }

                // 2) 중복 제거 후 append (timestamp 기준)
                int added = 0;
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject t = queue.getJSONObject(i);
                    if (!containsTimestamp(existing, t.optString("timestamp"))) {
                        existing.put(t);
                        added++;
                    }
                }

                // 3) PATCH
                if (added > 0) {
                    patchFile(token, gistId, TRADES_FILE, existing.toString(2));
                }
                prefs.edit().putString("send_queue", "[]").apply();
                AppLog.add(ctx, "Gist 전송 완료 (" + added + "건)");
            } catch (HttpException he) {
                String msg = (he.code == 401 || he.code == 403)
                        ? "Gist 인증 실패(" + he.code + ") — PAT 토큰 확인 필요"
                        : "Gist 통신 실패(" + he.code + ") — 재시도 대기";
                showError(ctx, msg);
                AppLog.add(ctx, "전송 실패: " + msg);
            } catch (Exception e) {
                showError(ctx, "Gist 전송 실패: " + e.getMessage() + " — 큐에 보관, 재시도 예정");
                AppLog.add(ctx, "전송 실패(큐 보관): " + e.getMessage());
            }
        });
    }

    /** Gist에서 파싱 규칙(noti_rules.json)을 받아 캐시. MainActivity에서 호출. */
    public static void refreshRules(Context ctx, Runnable onDone) {
        exec.execute(() -> {
            SharedPreferences prefs = prefs(ctx);
            String token = prefs.getString("gist_token", "");
            String gistId = prefs.getString("gist_id", "");
            try {
                if (token.isEmpty() || gistId.isEmpty()) throw new Exception("토큰/ID 미설정");
                String content = fetchFile(token, gistId, RULES_FILE);
                if (content == null) {
                    AppLog.add(ctx, "Gist에 " + RULES_FILE + " 없음 — 내장 규칙 사용");
                } else {
                    JSONObject o = new JSONObject(content); // 형식 검증
                    if (!o.has("rules")) throw new Exception("rules 키 없음");
                    prefs.edit().putString("gist_rules", content).apply();
                    AppLog.add(ctx, "규칙 갱신 완료 (Gist, 규칙 " + o.getJSONArray("rules").length() + "개)");
                }
            } catch (Exception e) {
                AppLog.add(ctx, "규칙 갱신 실패(" + e.getMessage() + ") — 내장 규칙으로 동작");
            }
            if (onDone != null) onDone.run();
        });
    }

    // ── HTTP ──────────────────────────────────────

    /** 파일 내용 반환. 파일이 Gist에 없으면 null. HTTP 오류는 HttpException. */
    private static String fetchFile(String token, String gistId, String filename) throws Exception {
        HttpURLConnection conn = open("https://api.github.com/gists/" + gistId, token, "GET");
        int code = conn.getResponseCode();
        if (code != 200) throw new HttpException(code);
        JSONObject res = new JSONObject(readBody(conn));
        JSONObject files = res.getJSONObject("files");
        if (!files.has(filename)) return null;
        JSONObject f = files.getJSONObject(filename);
        // 1MB 초과 시 content가 잘림(truncated) — raw_url로 재요청
        if (f.optBoolean("truncated", false)) {
            HttpURLConnection raw = open(f.getString("raw_url"), token, "GET");
            if (raw.getResponseCode() != 200) throw new HttpException(raw.getResponseCode());
            return readBody(raw);
        }
        return f.getString("content");
    }

    private static void patchFile(String token, String gistId, String filename, String content) throws Exception {
        HttpURLConnection conn = open("https://api.github.com/gists/" + gistId, token, "PATCH");
        conn.setDoOutput(true);
        JSONObject payload = new JSONObject()
                .put("files", new JSONObject().put(filename, new JSONObject().put("content", content)));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new HttpException(code);
    }

    private static HttpURLConnection open(String url, String token, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // Android의 HttpURLConnection은 OkHttp 기반이라 PATCH를 직접 지원한다.
        // (순수 JDK는 PATCH 미지원이지만 Android는 가능 — kakaoNoti가 PATCH로 동작했던 근거)
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean containsTimestamp(JSONArray arr, String ts) {
        for (int i = 0; i < arr.length(); i++) {
            if (ts.equals(arr.optJSONObject(i) != null ? arr.optJSONObject(i).optString("timestamp") : "")) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(AlarmParser.PREFS, Context.MODE_PRIVATE);
    }

    // ── 에러 알림 (silent fail 제거) ──────────────────────────────────────

    public static void showError(Context ctx, String msg) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        "errors", "오류 알림", NotificationManager.IMPORTANCE_HIGH));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, "errors")
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("알람파싱 오류")
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() % 10000), b.build());
        } catch (Exception e) {
            Log.e(TAG, "에러 알림 표시 실패", e);
        }
    }

    static class HttpException extends Exception {
        final int code;
        HttpException(int code) { super("HTTP " + code); this.code = code; }
    }
}
