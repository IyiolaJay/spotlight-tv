package io.github.iyiolajay.spotlight;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Inputs menu: HDMI ports, AV and Live TV from the TV input framework. HDMI/AV open as
 * passthrough channels, Live TV as the tuner's channel list; the TV's own Live TV app handles both.
 * Ports with nothing plugged in are listed but greyed.
 */
final class Inputs {
    private Inputs() {}

    static List<GlassMenu.Item> items(HomeActivity home) {
        List<GlassMenu.Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) home.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> ports = new ArrayList<>();
        TvInputInfo tuner = null;
        if (tim != null) {
            Set<String> seen = new HashSet<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null) continue; // HDMI-CEC devices behind a port
                if (info.getType() == TvInputInfo.TYPE_TUNER) {
                    if (tuner == null) tuner = info;
                } else if (info.isPassthroughInput() && info.getType() != TvInputInfo.TYPE_OTHER
                        && seen.add(label(home, info))) {
                    ports.add(info);
                }
            }
        }
        // HDMI first, then AV and the rest, each in label order
        ports.sort((a, b) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
            return ha != hb ? (ha ? -1 : 1) : label(home, a).compareTo(label(home, b));
        });
        for (TvInputInfo p : ports) {
            Intent target = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(p.getId()));
            boolean connected = tim.getInputState(p.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED;
            out.add(connected
                    ? GlassMenu.Item.of(label(home, p), () -> home.open(target))
                    : GlassMenu.Item.dimmed(label(home, p), () -> home.open(target)));
        }
        if (tuner != null || out.isEmpty()) {
            Intent live = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            out.add(GlassMenu.Item.of(home.getString(R.string.live_tv), () -> home.open(live)));
        }
        return out;
    }

    private static String label(Context c, TvInputInfo info) {
        CharSequence l = info.loadCustomLabel(c);
        if (l != null && l.length() > 0) return String.valueOf(l);
        if (info.getType() == TvInputInfo.TYPE_COMPOSITE) return c.getString(R.string.av);
        return String.valueOf(info.loadLabel(c));
    }
}
