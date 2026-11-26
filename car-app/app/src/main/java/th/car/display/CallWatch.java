package th.car.display;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.lang.reflect.Method;

/**
 * Phone calls arriving through the head unit's Bluetooth hands-free link.
 *
 * The head unit is the hands-free side: the phone tells it about each call, and it can ask the
 * phone to answer or hang up. Android does this in a part of the Bluetooth stack that has no public
 * interface, so everything here goes through reflection and is written to fail quietly. If it does
 * not work on a given head unit, the display still steps aside so the unit's own call screen shows.
 */
final class CallWatch {

    static final int ACTIVE = 0, HELD = 1, DIALING = 2, ALERTING = 3, INCOMING = 4, WAITING = 5, ENDED = 7;

    /** True from the first sign of a call until it ends: nothing else should make sound meanwhile. */
    static volatile boolean inCall;

    interface Listener { void call(int state, String number, String name); }

    private static final String CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED";
    private static final String EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL";
    private static final int HANDS_FREE_UNIT = 16;      // BluetoothProfile.HEADSET_CLIENT, not in the public constants

    private final Context app;
    private final Listener listener;
    private Object proxy, lastCall;
    private BluetoothDevice phone;
    private BroadcastReceiver receiver;

    CallWatch(Context c, Listener l) { app = c.getApplicationContext(); listener = l; }

    void start() {
        try {
            receiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { changed(i); } };
            app.registerReceiver(receiver, new IntentFilter(CALL_CHANGED));
            NowPlaying.get().note("สายโทร: เริ่มเฝ้าดู");
        } catch (Throwable e) { NowPlaying.get().note("สายโทร: เฝ้าดูไม่ได้ " + e.getClass().getSimpleName()); }
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) bt.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile p) { proxy = p; NowPlaying.get().note("สายโทร: สั่งรับ/วางสายได้"); }
                @Override public void onServiceDisconnected(int profile) { proxy = null; }
            }, HANDS_FREE_UNIT);
        } catch (Throwable e) { NowPlaying.get().note("สายโทร: ไม่มีช่องสั่งรับ/วางสาย " + e.getClass().getSimpleName()); }
    }

    void stop() {
        try { if (receiver != null) app.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        try { if (proxy != null) BluetoothAdapter.getDefaultAdapter().closeProfileProxy(HANDS_FREE_UNIT, (BluetoothProfile) proxy); } catch (Throwable ignored) { }
        inCall = false;
    }

    private void changed(Intent i) {
        try {
            Object call = i.getParcelableExtra(EXTRA_CALL);
            if (call == null) return;
            int state = (Integer) call.getClass().getMethod("getState").invoke(call);
            String number = String.valueOf(call.getClass().getMethod("getNumber").invoke(call));
            try { phone = (BluetoothDevice) call.getClass().getMethod("getDevice").invoke(call); } catch (Throwable ignored) { }
            if (phone == null) phone = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            lastCall = call;
            inCall = state != ENDED;
            NowPlaying.get().note("สายโทร: สถานะ " + state + (number.length() > 4 ? " เบอร์ลงท้าย " + number.substring(number.length() - 4) : ""));
            listener.call(state, "null".equals(number) ? "" : number, nameFor(number));
        } catch (Throwable e) { NowPlaying.get().note("สายโทร: อ่านไม่ได้ " + e.getClass().getSimpleName()); }
    }

    /** The contact's name, if the head unit holds a copy of the phone's contacts and the app may read them. */
    private String nameFor(String number) {
        if (number == null || number.length() < 3) return "";
        Cursor c = null;
        try {
            c = app.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) { } finally { if (c != null) c.close(); }
        return "";
    }

    private boolean invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method m = proxy.getClass().getMethod(name, types);
            Object r = m.invoke(proxy, args);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable e) { return false; }
    }

    /** Ask the phone to answer. Returns false when this head unit does not let the app do it. */
    boolean answer() {
        if (proxy == null || phone == null) return false;
        return invoke("acceptCall", new Class<?>[]{BluetoothDevice.class, int.class}, phone, 0);
    }

    /** Refuse a ringing call, or end the one in progress. */
    boolean hangUp(boolean ringing) {
        if (proxy == null || phone == null) return false;
        if (ringing && invoke("rejectCall", new Class<?>[]{BluetoothDevice.class}, phone)) return true;
        if (lastCall != null && invoke("terminateCall", new Class<?>[]{BluetoothDevice.class, lastCall.getClass()}, phone, lastCall)) return true;
        return invoke("terminateCall", new Class<?>[]{BluetoothDevice.class, int.class}, phone, 0);
    }
}
