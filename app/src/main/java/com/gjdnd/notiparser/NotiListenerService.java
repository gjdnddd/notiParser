package com.gjdnd.notiparser;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotiListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        AppLog.add(this, "알림 리스너 연결됨");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        // 자기 자신의 알림(에러 알림 등)은 무시 — 무한 루프 방지
        if (getPackageName().equals(pkg)) return;

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence normal = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);

        // big/normal 중 더 긴 쪽 선택 (kakaoNoti는 big 우선이라 한 줄만 잡히는 버그 있었음)
        String body = "";
        if (big != null) body = big.toString();
        if (normal != null && normal.length() > body.length()) body = normal.toString();

        String text = (title != null ? title + "\n" : "") + body;
        if (text.trim().isEmpty()) return;

        AlarmParser.handle(getApplicationContext(), pkg, text);
    }
}
