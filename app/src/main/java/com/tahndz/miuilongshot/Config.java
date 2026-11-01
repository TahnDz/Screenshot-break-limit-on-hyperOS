package com.tahndz.miuilongshot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

/**
 * Cau hinh duoc chia giua app module va app bi hook (com.miui.screenshot).
 *
 * App module ghi 1 json vao SharedPreferences + export qua ConfigProvider,
 * app bi hook doc lai qua provider (fallback: file json /data/local/tmp).
 */
public final class Config {

    public static final String PKG = "com.tahndz.miuilongshot";
    public static final String PREFS = "config";
    public static final String KEY_JSON = "json";
    public static final String AUTHORITY = PKG + ".config";
    public static final String FILE_FALLBACK = "/data/local/tmp/miuilongshot.json";

    /* keys */
    public static final String K_ENABLED = "enabled";
    public static final String K_MODE = "mode";                   // screens | px | unlimited
    public static final String K_SCREENS = "screens";
    public static final String K_PX = "px";
    public static final String K_SCROLL_MS = "scrollMs";
    public static final String K_DELAY_MS = "delayMs";
    public static final String K_SWIPE = "swipe";
    public static final String K_FLING = "fling";
    public static final String K_DOWNSCALE = "downscale";
    public static final String K_MAX_BMP = "maxBitmapHeight";

    /* modes */
    public static final String MODE_SCREENS = "screens";
    public static final String MODE_PX = "px";
    public static final String MODE_UNLIMITED = "unlimited";

    private Config() {
    }

    public static JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put(K_ENABLED, true);
            o.put(K_MODE, MODE_SCREENS);
            o.put(K_SCREENS, 12);
            o.put(K_PX, 30000);
            o.put(K_SCROLL_MS, 120);
            o.put(K_DELAY_MS, 180);
            o.put(K_SWIPE, 1.0d);
            o.put(K_FLING, 2.0d);
            o.put(K_DOWNSCALE, true);
            o.put(K_MAX_BMP, 30000);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static String readStored(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String s = p.getString(KEY_JSON, null);
            if (s != null && s.length() > 0) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        return defaults().toString();
    }

    public static void writeStored(Context ctx, String json) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(KEY_JSON, json).commit();
        } catch (Throwable ignored) {
        }
        // ban sao world-readable cho XSharedPreferences (neu SELinux cho phep)
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            File f = new File(dir, PREFS + ".xml");
            if (f.exists()) {
                f.setReadable(true, false);
                f.setWritable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
