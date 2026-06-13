package com.gjdnd.notiparser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 앱 내 이벤트 로그 + 수집 모드(알림 원본 보관).
 * 둘 다 SharedPreferences에 최근 50개까지 보관 — 새 증권사 규칙 작성과 장애 진단용.
 */
public class AppLog {
    private static final int MAX = 50;

    /** 이벤트 로그 (파싱 성공/실패, 전송 결과 등) */
    public static synchronized void add(Context ctx, String msg) {
        append(ctx, "event_log", time() + " " + msg);
    }

    /** 수집 모드: 트리거에 걸린 알림 원본 텍스트 저장 */
    public static synchronized void addRaw(Context ctx, String pkg, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", time());
            o.put("pkg", pkg);
            o.put("text", text);
            append(ctx, "raw_log", o.toString());
        } catch (Exception ignore) {}
    }

    public static JSONArray getEvents(Context ctx) { return load(ctx, "event_log"); }
    public static JSONArray getRaw(Context ctx) { return load(ctx, "raw_log"); }

    private static void append(Context ctx, String key, String entry) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(AlarmParser.PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            arr.put(entry);
            // 최근 MAX개만 유지
            while (arr.length() > MAX) arr.remove(0);
            prefs.edit().putString(key, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static JSONArray load(Context ctx, String key) {
        try {
            return new JSONArray(ctx.getSharedPreferences(AlarmParser.PREFS, Context.MODE_PRIVATE)
                    .getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String time() {
        return new SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(new Date());
    }
}
