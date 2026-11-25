package th.car.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** Brings the floating card back after the head unit starts, when that option is on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (!App.prefs(c).getBoolean("autostart", false)) return;
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
            Intent overlay = new Intent(c, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(overlay); else c.startService(overlay);
            Intent cameraApp = App.findCameraApp(c);
            if (cameraApp != null) c.startActivity(cameraApp);
        } catch (Throwable ignored) { }
    }
}
