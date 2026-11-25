package th.car.display;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Holding notification access is what lets an app read other apps' "now playing" sessions.
 * Media notifications are also read directly, as a fallback for players without a session.
 */
public class MusicListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        NowPlaying.get().note("สิทธิ์อ่านการแจ้งเตือน: เชื่อมต่อแล้ว");
        NowPlaying.get().watchSessions(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        try {
            Notification n = posted.getNotification();
            if (n == null || getPackageName().equals(posted.getPackageName())) return;
            String pkg = posted.getPackageName().toLowerCase();
            boolean media = Notification.CATEGORY_TRANSPORT.equals(n.category) || pkg.contains("music") || pkg.contains("bt") || pkg.contains("bluetooth") || pkg.contains("audio");
            if (!media) return;
            Bundle x = n.extras;
            CharSequence title = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x == null ? null : x.getCharSequence(Notification.EXTRA_TEXT);
            if (title != null && title.length() > 0) NowPlaying.get().offer("การแจ้งเตือนของ " + posted.getPackageName(), title.toString(), text == null ? null : text.toString(), null, false);
        } catch (Throwable ignored) { }
    }
}
