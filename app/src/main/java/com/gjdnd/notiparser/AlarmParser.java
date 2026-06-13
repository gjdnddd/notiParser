package com.gjdnd.notiparser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알림 텍스트 → 파싱 규칙 매칭 → mmNoti 호환 JSON 생성 → GistManager로 전달.
 *
 * 규칙 로드 순서: Gist에서 받아 캐시한 noti_rules.json → 없거나 깨지면 앱 내장 default_rules.json.
 * Gist 로드가 실패해도 내장 규칙으로 항상 동작한다 (kakaoNoti의 단일 장애점 제거).
 */
public class AlarmParser {
    private static final String TAG = "NotiParser";
    static final String PREFS = "notiparser_prefs";

    public static void handle(Context ctx, String pkg, String text) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject rules = loadRules(ctx);
            String trigger = prefs.getString("trigger", rules.optString("trigger", "체결"));
            if (!text.contains(trigger)) return;

            // 패키지 제외 (기본: 카카오톡 제외, 설정으로 포함 가능)
            boolean includeKakao = prefs.getBoolean("include_kakao", false);
            JSONArray excludes = rules.optJSONArray("excludePackages");
            if (excludes != null) {
                for (int i = 0; i < excludes.length(); i++) {
                    String ex = excludes.getString(i);
                    if (pkg.equals(ex)) {
                        if (ex.equals("com.kakao.talk") && includeKakao) continue;
                        AppLog.add(ctx, "제외 패키지 무시: " + pkg);
                        return;
                    }
                }
            }

            // 수집 모드: 트리거에 걸린 알림 원본은 항상 기록 (새 증권사 규칙 작성용)
            AppLog.addRaw(ctx, pkg, text);

            JSONArray ruleArr = rules.optJSONArray("rules");
            if (ruleArr == null) return;
            for (int i = 0; i < ruleArr.length(); i++) {
                JSONObject rule = ruleArr.getJSONObject(i);
                String requires = rule.optString("requires", "");
                if (!requires.isEmpty() && !text.contains(requires)) continue;

                JSONObject parsed = parseFields(text, rule.getJSONObject("fields"));
                // 필수 필드: 단가·수량·매매구분 — 하나라도 없으면 다음 규칙 시도
                if (!parsed.has("체결단가") || !parsed.has("체결수량") || !parsed.has("매매구분")) {
                    AppLog.add(ctx, "규칙 '" + rule.optString("name") + "' 필수 필드 누락 — 건너뜀");
                    continue;
                }

                JSONObject trade = normalize(ctx, parsed, rules, rule.optString("name"));
                AppLog.add(ctx, "파싱 성공 [" + rule.optString("name") + "] " +
                        trade.optString("종목명") + " " + trade.optString("매매구분") + " " +
                        trade.optString("체결수량"));
                GistManager.enqueueAndSend(ctx, trade);
                return;
            }
            AppLog.add(ctx, "매칭되는 규칙 없음 (" + pkg + ")");
        } catch (Exception e) {
            Log.e(TAG, "handle 실패", e);
            AppLog.add(ctx, "파싱 오류: " + e.getMessage());
        }
    }

    /** 테스트 파싱: Gist 전송 없이 파싱 결과 문자열만 반환 (MainActivity 테스트 버튼용) */
    public static String dryRun(Context ctx, String text) {
        try {
            JSONObject rules = loadRules(ctx);
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String trigger = prefs.getString("trigger", rules.optString("trigger", "체결"));
            if (!text.contains(trigger)) return "트리거 '" + trigger + "' 미포함 — 무시됨";

            JSONArray ruleArr = rules.optJSONArray("rules");
            if (ruleArr == null) return "규칙 없음";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ruleArr.length(); i++) {
                JSONObject rule = ruleArr.getJSONObject(i);
                String requires = rule.optString("requires", "");
                if (!requires.isEmpty() && !text.contains(requires)) {
                    sb.append("✗ ").append(rule.optString("name")).append(": requires 미일치\n");
                    continue;
                }
                JSONObject parsed = parseFields(text, rule.getJSONObject("fields"));
                if (!parsed.has("체결단가") || !parsed.has("체결수량") || !parsed.has("매매구분")) {
                    sb.append("✗ ").append(rule.optString("name"))
                            .append(": 필수 필드 누락 → ").append(parsed.toString()).append('\n');
                    continue;
                }
                JSONObject trade = normalize(ctx, parsed, rules, rule.optString("name"));
                return "✓ 규칙 [" + rule.optString("name") + "] 매칭\n\n" + trade.toString(2);
            }
            return sb.length() > 0 ? sb.toString() : "매칭되는 규칙 없음";
        } catch (Exception e) {
            return "오류: " + e.getMessage();
        }
    }

    /** 규칙의 필드별 정규식으로 텍스트에서 값 추출 */
    private static JSONObject parseFields(String text, JSONObject fields) throws Exception {
        JSONObject result = new JSONObject();
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Matcher m = Pattern.compile(fields.getString(key)).matcher(text);
            if (m.find()) result.put(key, m.group(1).trim());
        }
        return result;
    }

    /** 파싱 결과를 mmNoti(meritz_trades.json) 호환 형식으로 정규화 */
    private static JSONObject normalize(Context ctx, JSONObject parsed, JSONObject rules, String source) throws Exception {
        JSONObject t = new JSONObject();
        long now = System.currentTimeMillis();

        String name = parsed.optString("종목명", "");
        // 종목명에 (TICKER)가 없으면 tickerMap에서 찾아 붙임 — mmNoti가 (TICKER)로 심볼 인식
        if (!name.matches(".*\\([A-Z0-9]+\\).*")) {
            String ticker = mapTicker(name, rules.optJSONObject("tickerMap"));
            if (ticker != null) {
                name = name + "(" + ticker + ")";
            } else {
                GistManager.showError(ctx, "티커 매핑 없음: " + name + " — 규칙 파일에 추가 필요");
                AppLog.add(ctx, "⚠ 티커 매핑 없음: " + name);
            }
        }

        String priceStr = parsed.optString("체결단가", "0").replace(",", "");
        String qtyStr = parsed.optString("체결수량", "0").replaceAll("[^0-9]", "");
        double price = Double.parseDouble(priceStr);
        int qty = Integer.parseInt(qtyStr.isEmpty() ? "0" : qtyStr);

        // 체결금액이 알림에 없으면 수량 × 단가로 계산 (메리츠 앱 알림 케이스)
        String amount = parsed.optString("체결금액", "").replace(",", "");
        if (amount.isEmpty()) {
            amount = String.format(Locale.US, "%.2f", price * qty);
        }

        String date = parsed.optString("체결일자", "");
        if (date.isEmpty()) {
            date = new SimpleDateFormat("MM/dd", Locale.US).format(new Date(now));
        }

        t.put("계좌명", parsed.optString("계좌명", ""));
        t.put("계좌번호", parsed.optString("계좌번호", ""));
        t.put("종목명", name);
        t.put("매매구분", parsed.optString("매매구분"));
        t.put("체결단가", "USD " + priceStr);
        t.put("주문수량", parsed.optString("주문수량", qty + "") .replaceAll("[^0-9]", "") + "주");
        t.put("체결수량", qty + "주");
        t.put("체결금액", "USD " + amount);
        t.put("체결일자", date);
        t.put("timestamp", String.valueOf(now));
        t.put("source", source);
        return t;
    }

    /** 종목명에 tickerMap 키워드가 포함되어 있으면 해당 티커 반환 */
    private static String mapTicker(String name, JSONObject tickerMap) {
        if (tickerMap == null || name.isEmpty()) return null;
        String upper = name.toUpperCase(Locale.US);
        Iterator<String> keys = tickerMap.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (upper.contains(key.toUpperCase(Locale.US))) return tickerMap.optString(key);
        }
        return null;
    }

    /** Gist 캐시 규칙 → 실패 시 내장 기본 규칙 */
    static JSONObject loadRules(Context ctx) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString("gist_rules", "");
        if (!cached.isEmpty()) {
            try {
                JSONObject o = new JSONObject(cached);
                if (o.has("rules")) return o;
            } catch (Exception ignore) { /* 캐시 깨짐 → 내장 규칙 */ }
        }
        return loadDefaultRules(ctx);
    }

    static JSONObject loadDefaultRules(Context ctx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("default_rules.json"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return new JSONObject(sb.toString());
    }
}
