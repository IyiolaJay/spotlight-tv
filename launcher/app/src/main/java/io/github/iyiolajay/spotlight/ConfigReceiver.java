package io.github.iyiolajay.spotlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.UserManager;

/**
 * Lets the shell set Spotlight up without a rebuild. Only senders holding DUMP (the ADB shell, the
 * system) can reach it:
 *
 *   am broadcast -n io.github.iyiolajay.spotlight/.ConfigReceiver -a io.github.iyiolajay.spotlight.CONFIG \
 *       --es name Ada                 the greeting and the signature ("none": no name)
 *   ... --es favourites com.netflix.ninja,com.google.android.youtube.tv
 *   ... --es hidden uk.co.freeview.bbc,uk.co.freeview.itv
 *   ... --es boot_to_home on          come back over Live TV at power-on ("off" to stop)
 *   ... --es <any of them> default    back to config.xml
 *   ... (no extras)                   just reload the apps
 */
public class ConfigReceiver extends BroadcastReceiver {
    static final String PREFS = "home";

    /**
     * Home's settings, in device-protected storage: Home runs from the first seconds of boot, before
     * the user is unlocked and ordinary app storage can be read. Settings saved by older versions
     * move over the first time it runs unlocked.
     */
    static SharedPreferences prefs(Context c) {
        Context device = c.createDeviceProtectedStorageContext();
        if (c.getSystemService(UserManager.class).isUserUnlocked()) device.moveSharedPreferencesFrom(c, PREFS);
        return device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String key : new String[] {"name", "favourites", "hidden", "boot_to_home"}) {
            String v = intent.getStringExtra(key);
            if (v == null) continue;
            if ("default".equals(v)) e.remove(key);
            else if ("boot_to_home".equals(key)) e.putBoolean(key, "on".equals(v) || "true".equals(v));
            else if ("name".equals(key) && "none".equals(v)) e.putString(key, "");
            else e.putString(key, v);
        }
        e.putLong("ping", System.currentTimeMillis()).apply();
    }
}
