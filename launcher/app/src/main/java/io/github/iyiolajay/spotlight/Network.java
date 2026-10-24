package io.github.iyiolajay.spotlight;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.util.Locale;

/** What the Wi-Fi pill and its menu show. Read when needed; nothing polls. */
final class Network {
    private Network() {}

    static final class Status {
        boolean connected, ethernet;
        /** The Wi-Fi name; null when Android won't say (it needs location on) or not on Wi-Fi. */
        String ssid;
        /** Signal from 0 (weak) to 1 (excellent); -1 when not on Wi-Fi. */
        float signal = -1;
        String ip, privateDns;
    }

    static Status read(Context c) {
        Status s = new Status();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        android.net.Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        s.connected = nc != null;
        s.ethernet = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (s.connected && !s.ethernet) {
            WifiManager wm = c.getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null) {
                s.ssid = ssid(info);
                if (Build.VERSION.SDK_INT >= 30) {
                    s.signal = wm.calculateSignalLevel(info.getRssi()) / (float) Math.max(1, wm.getMaxSignalLevel());
                } else {
                    s.signal = WifiManager.calculateSignalLevel(info.getRssi(), 5) / 4f;
                }
            }
        }
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp != null) {
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    s.ip = a.getAddress().getHostAddress();
                    break;
                }
            }
            if (lp.isPrivateDnsActive()) s.privateDns = lp.getPrivateDnsServerName();
        }
        return s;
    }

    private static String ssid(WifiInfo info) {
        String s = info.getSSID();
        if (s == null || s.isEmpty() || WifiManager.UNKNOWN_SSID.equals(s)) return null;
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    /** The pill's label: the network name, or what kind of connection it is. */
    static String label(Context c, Status s) {
        if (!s.connected) return c.getString(R.string.offline);
        if (s.ethernet) return c.getString(R.string.ethernet);
        return s.ssid != null ? s.ssid : c.getString(R.string.wifi);
    }

    static int icon(Status s) {
        return s.ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
    }

    /** The top of the Wi-Fi menu: name and state, then signal and address, then the ad-blocking DNS. */
    static View header(Context c, Stage st, Status s) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(st.px(20), st.px(16), st.px(20), st.px(14));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(c, st, icon(s), 26, 0xFFF5F5F7));
        TextView name = st.text(c, 27, 500, 0xFFF5F5F7, -.01f);
        name.setText(label(c, s));
        top.addView(name, margin(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f), st.px(12), 0));
        View dot = new View(c);
        GradientDrawable light = new GradientDrawable();
        light.setShape(GradientDrawable.OVAL);
        light.setColor(s.connected ? 0xFF30D158 : 0xFF8E8E93);
        dot.setBackground(light);
        top.addView(dot, margin(new LinearLayout.LayoutParams(st.px(8), st.px(8)), st.px(12), 0));
        TextView state = st.text(c, 13, 600, 0xCCEBEBF5, .18f);
        state.setAllCaps(true);
        state.setText(s.connected ? R.string.connected : R.string.offline);
        top.addView(state, margin(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), st.px(8), 0));
        box.addView(top);

        StringBuilder line = new StringBuilder();
        if (s.signal >= 0) line.append(c.getString(signalWord(s.signal)));
        if (s.ip != null) line.append(line.length() > 0 ? "  ·  " : "").append(s.ip);
        if (line.length() > 0) box.addView(detail(c, st, 0, line));

        if (s.privateDns != null) {
            String dns = s.privateDns.toLowerCase(Locale.ROOT).contains("adguard")
                    ? c.getString(R.string.ad_blocking, "AdGuard DNS")
                    : c.getString(R.string.private_dns, s.privateDns);
            box.addView(detail(c, st, R.drawable.ic_shield, dns));
        }
        return box;
    }

    private static int signalWord(float signal) {
        return signal >= .75f ? R.string.signal_excellent : signal >= .5f ? R.string.signal_good
                : signal >= .25f ? R.string.signal_fair : R.string.signal_weak;
    }

    private static View detail(Context c, Stage st, int icon, CharSequence text) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != 0) row.addView(icon(c, st, icon, 18, 0xB8EBEBF5));
        TextView t = st.text(c, 18, 400, 0xB8EBEBF5, 0);
        t.setFontFeatureSettings("tnum");
        t.setText(text);
        row.addView(t, margin(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), icon != 0 ? st.px(10) : 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = st.px(10);
        row.setLayoutParams(lp);
        return row;
    }

    private static ImageView icon(Context c, Stage st, int res, float size, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(st.px(size), st.px(size)));
        return v;
    }

    private static LinearLayout.LayoutParams margin(LinearLayout.LayoutParams lp, int start, int end) {
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        return lp;
    }
}
