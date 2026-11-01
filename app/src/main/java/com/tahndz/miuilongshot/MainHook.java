package com.tahndz.miuilongshot;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.Scroller;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS / MIUI Screenshot (com.miui.screenshot)
 *
 * Cac diem da doc tu c.apk:
 *  - Gioi han chieu cao anh dai: com.miui.screenshot.accessibility.q.I(Context)
 *      q.r = screenHeight * 8  (~20k px)  -> bo bang gia tri cau hinh
 *  - Check dung cuon: com.miui.screenshot.accessibility.r.M()
 *      (t: AtomicInteger tong chieu cao, H: int duoi) so voi q.r
 *  - Toc do cuon: r$c.run() (field int e = thoi gian swipe, mac dinh 350ms qua u1.A.j)
 *  - Delay giua 2 lan chup: r$e.run() -> Thread.sleep(650)
 *  - Buoc cuon: q.k() -> q.l (Rect) chieu cao vung swipe
 *  - Luot xem anh dai: ScreenshotScrollView.onTouchEvent -> Scroller.fling(-n(7000))
 *  - Ghep anh dai: ScreenshotScrollView.i() -> Bitmap.createBitmap()
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "MiuiLongShot";
    private static final String TARGET_PKG = "com.miui.screenshot";

    private static final String CLS_HELPER = "com.miui.screenshot.accessibility.q";
    private static final String CLS_PRODUCER = "com.miui.screenshot.accessibility.r";
    private static final String CLS_UTIL = "u1.A";
    private static final String CLS_SCROLL_VIEW = "com.miui.screenshot.ScreenshotScrollView";

    private static final Object CFG_LOCK = new Object();
    private static JSONObject sCfg;
    private static long sCfgAt;

    private static volatile Context sContext;
    private static Field sMaxHeightField;
    private static int sOriginalMax;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        log("loaded, process=" + lpparam.processName);
        ClassLoader cl = lpparam.classLoader;
        hookHelperMaxHeight(cl);
        hookProducerLimit(cl);
        hookScrollDuration(cl);
        hookCaptureDelay();
        hookSwipeStep(cl);
        hookPreviewFling();
        hookLongBitmapFallback(cl);
    }

    /* ------------------------------------------------------------------ */
    /* 1. Bo gioi han chieu cao anh dai                                    */
    /* ------------------------------------------------------------------ */

    private void hookHelperMaxHeight(ClassLoader cl) {
        final Class<?> q = findClass(CLS_HELPER, cl);
        if (q == null) {
            return;
        }
        for (final Method m : q.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !Context.class.equals(p[0])) {
                continue;
            }
            if (!"I".equals(m.getName())) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                        sContext = ((Context) param.args[0]).getApplicationContext();
                    }
                    applyMaxHeight(q);
                }
            });
            log("hooked " + CLS_HELPER + "#I(Context)");
        }
    }

    private static void applyMaxHeight(Class<?> q) {
        try {
            JSONObject cfg = cfg();
            if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                return;
            }
            Field f = sMaxHeightField;
            if (f == null) {
                f = findMaxHeightField(q);
                if (f == null) {
                    log("max height field not found");
                    return;
                }
                sMaxHeightField = f;
            }
            int cur = f.getInt(null);
            if (sOriginalMax == 0 && cur > 0 && cur % 8 == 0 && cur >= screenHeightGuess() * 4) {
                sOriginalMax = cur;
                log("original max height = " + cur);
            }
            int limit = resolveLimit(cfg);
            if (limit < 0) {
                limit = 2000000; // thuc te: khong gioi han
            }
            f.setInt(null, limit);
            log("max height -> " + limit);
        } catch (Throwable t) {
            log("applyMaxHeight: " + t);
        }
    }

    /**
     * Tim static int field chua gioi han chieu cao ( = screenHeight * 8 ).
     * Chon field static int co gia tri lon nhat trong class q.
     */
    private static Field findMaxHeightField(Class<?> q) {
        Field best = null;
        int bestVal = 0;
        for (Field f : q.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() != int.class) {
                continue;
            }
            try {
                f.setAccessible(true);
                int v = f.getInt(null);
                if (v > bestVal) {
                    bestVal = v;
                    best = f;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    /**
     * Hook check cua producer: r.M() -> boolean.
     * Neu khong patch duoc field thi van ep dung gioi han cua module.
     */
    private void hookProducerLimit(ClassLoader cl) {
        final Class<?> prod = findClass(CLS_PRODUCER, cl);
        if (prod == null) {
            return;
        }
        for (final Method m : prod.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (!boolean.class.equals(m.getReturnType())) {
                continue;
            }
            if (!"M".equals(m.getName())) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        JSONObject cfg = cfg();
                        if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                            return;
                        }
                        int limit = resolveLimit(cfg);
                        if (limit < 0) {
                            param.setResult(Boolean.FALSE); // khong gioi han
                            return;
                        }
                        Integer total = readTotalHeight(param.thisObject);
                        if (total == null) {
                            return;
                        }
                        param.setResult(Boolean.valueOf(total > limit));
                    } catch (Throwable t) {
                        log("producerLimit: " + t);
                    }
                }
            });
            log("hooked " + CLS_PRODUCER + "#M()");
        }
    }

    private static Integer readTotalHeight(Object producer) {
        if (producer == null) {
            return null;
        }
        try {
            Field best = null;
            int bestVal = Integer.MIN_VALUE;
            for (Field f : producer.getClass().getDeclaredFields()) {
                if (!AtomicInteger.class.equals(f.getType())) {
                    continue;
                }
                if ("t".equals(f.getName())) {
                    best = f;
                    break;
                }
                f.setAccessible(true);
                Object o = f.get(producer);
                if (o instanceof AtomicInteger) {
                    int v = ((AtomicInteger) o).get();
                    if (v > bestVal) {
                        bestVal = v;
                        best = f;
                    }
                }
            }
            if (best != null) {
                best.setAccessible(true);
                Object o = best.get(producer);
                if (o instanceof AtomicInteger) {
                    return ((AtomicInteger) o).get();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 2. Tang toc do cuon                                                 */
    /* ------------------------------------------------------------------ */

    /** 2a. Inner ScrollTask cua producer: field int duy nhat = thoi gian swipe. */
    private void hookScrollDuration(ClassLoader cl) {
        final Class<?> prod = findClass(CLS_PRODUCER, cl);
        if (prod != null) {
            for (final Class<?> inner : prod.getDeclaredClasses()) {
                Method run = null;
                for (Method m : inner.getDeclaredMethods()) {
                    if ("run".equals(m.getName()) && m.getParameterCount() == 0
                            && void.class.equals(m.getReturnType())) {
                        run = m;
                        break;
                    }
                }
                if (run == null) {
                    continue;
                }
                final Field duration = uniqueIntField(inner);
                if (duration == null) {
                    continue;
                }
                XposedBridge.hookMethod(run, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            JSONObject cfg = cfg();
                            if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                                return;
                            }
                            int ms = cfg.optInt(Config.K_SCROLL_MS, 120);
                            if (ms <= 0) {
                                return;
                            }
                            duration.setAccessible(true);
                            Object cur = duration.get(param.thisObject);
                            if (cur instanceof Integer && ((Integer) cur) > ms) {
                                duration.setInt(param.thisObject, ms);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("hooked scroll task " + inner.getName());
            }
        }

        // 2b. u1.A#j(Context) tra ve thoi gian cuon theo app (mac dinh 350ms)
        final Class<?> util = findClass(CLS_UTIL, cl);
        if (util != null) {
            for (final Method m : util.getDeclaredMethods()) {
                if (!"j".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || !Context.class.equals(p[0])) {
                    continue;
                }
                if (!Integer.class.equals(m.getReturnType())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            JSONObject cfg = cfg();
                            if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                                return;
                            }
                            int ms = cfg.optInt(Config.K_SCROLL_MS, 120);
                            if (ms <= 0) {
                                return;
                            }
                            Object r = param.getResult();
                            if (r instanceof Integer && ((Integer) r) > ms) {
                                param.setResult(Integer.valueOf(ms));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("hooked " + CLS_UTIL + "#j(Context)");
            }
        }
    }

    /**
     * 2c. Thread.sleep(650) trong producer sau moi lan cuon.
     * Chi clamp khi stack thuoc producer cua app screenshot.
     */
    private void hookCaptureDelay() {
        for (final Method m : Thread.class.getDeclaredMethods()) {
            if (!"sleep".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != long.class) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        JSONObject cfg = cfg();
                        if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                            return;
                        }
                        long ms = (Long) param.args[0];
                        if (ms < 300L) {
                            return;
                        }
                        if (!inScreenshotStack()) {
                            return;
                        }
                        int d = cfg.optInt(Config.K_DELAY_MS, 180);
                        if (d >= 0 && d < ms) {
                            param.args[0] = (long) d;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("hooked Thread.sleep(long)");
        }
    }

    private static boolean inScreenshotStack() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("com.miui.screenshot.accessibility.")) {
                return true;
            }
            if (cn.equals(CLS_SCROLL_VIEW)) {
                return true;
            }
        }
        return false;
    }

    /** 2d. Buoc cuon: q.k() -> scale chieu cao Rect swipe (q.l). */
    private void hookSwipeStep(ClassLoader cl) {
        final Class<?> q = findClass(CLS_HELPER, cl);
        if (q == null) {
            return;
        }
        for (final Method m : q.getDeclaredMethods()) {
            if (!"k".equals(m.getName()) || m.getParameterCount() != 0
                    || !void.class.equals(m.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        JSONObject cfg = cfg();
                        if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                            return;
                        }
                        float factor = (float) cfg.optDouble(Config.K_SWIPE, 1.0d);
                        if (factor <= 1.001f) {
                            return;
                        }
                        Field rf = swipeRectField(q);
                        if (rf == null) {
                            return;
                        }
                        Object o = rf.get(null);
                        if (!(o instanceof Rect)) {
                            return;
                        }
                        Rect rect = (Rect) o;
                        int h = rect.height();
                        if (h <= 0) {
                            return;
                        }
                        int nh = Math.round(h * factor);
                        int cy = rect.centerY();
                        rect.top = cy - nh / 2;
                        rect.bottom = rect.top + nh;
                    } catch (Throwable t) {
                        log("swipeStep: " + t);
                    }
                }
            });
            log("hooked " + CLS_HELPER + "#k()");
        }
    }

    private static Field swipeRectField(Class<?> q) {
        for (Field f : q.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            if (Rect.class.equals(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** 2e. Luot xem anh dai: Scroller.fling() goi tu ScreenshotScrollView. */
    private void hookPreviewFling() {
        for (final Method m : Scroller.class.getDeclaredMethods()) {
            if (!"fling".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 8 || p[3] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        JSONObject cfg = cfg();
                        if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                            return;
                        }
                        float factor = (float) cfg.optDouble(Config.K_FLING, 2.0d);
                        if (factor <= 1.001f) {
                            return;
                        }
                        if (!inScreenshotStack()) {
                            return;
                        }
                        int vy = (Integer) param.args[3];
                        param.args[3] = (int) (vy * factor);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("hooked Scroller#fling");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3. Auto thu nho khi bitmap dai qua gioi han he thong                 */
    /* ------------------------------------------------------------------ */

    private void hookLongBitmapFallback(ClassLoader cl) {
        final Class<?> sv = findClass(CLS_SCROLL_VIEW, cl);
        if (sv == null) {
            return;
        }
        for (final Method m : sv.getDeclaredMethods()) {
            if (!"i".equals(m.getName()) || m.getParameterCount() != 0
                    || !Bitmap.class.equals(m.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof Bitmap) {
                        return;
                    }
                    try {
                        JSONObject cfg = cfg();
                        if (!cfg.optBoolean(Config.K_ENABLED, true)) {
                            return;
                        }
                        if (!cfg.optBoolean(Config.K_DOWNSCALE, true)) {
                            return;
                        }
                        Bitmap b = buildScaledLongBitmap(param.thisObject,
                                cfg.optInt(Config.K_MAX_BMP, 30000));
                        if (b != null) {
                            param.setResult(b);
                            log("fallback bitmap " + b.getWidth() + "x" + b.getHeight());
                        }
                    } catch (Throwable t) {
                        log("fallback: " + t);
                    }
                }
            });
            log("hooked " + CLS_SCROLL_VIEW + "#i()");
        }
    }

    private static Bitmap buildScaledLongBitmap(Object scrollView, int maxHeight) {
        if (scrollView == null) {
            return null;
        }
        Object drawable = null;
        for (Field f : scrollView.getClass().getDeclaredFields()) {
            if (Bitmap.class.equals(f.getType())) {
                continue;
            }
            if (!Drawable.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(scrollView);
                if (v != null) {
                    drawable = v;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (drawable == null) {
            return null;
        }
        Bitmap[] pages = null;
        for (Method m : drawable.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || !Bitmap[].class.equals(m.getReturnType())) {
                continue;
            }
            try {
                m.setAccessible(true);
                Object r = m.invoke(drawable);
                if (r instanceof Bitmap[]) {
                    pages = (Bitmap[]) r;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (pages == null || pages.length == 0) {
            return null;
        }
        int total = 0;
        int width = 0;
        for (Bitmap p : pages) {
            if (p == null || p.isRecycled()) {
                continue;
            }
            total += p.getHeight();
            width = Math.max(width, p.getWidth());
        }
        if (total <= 0 || width <= 0) {
            return null;
        }
        if (maxHeight <= 0) {
            maxHeight = 30000;
        }
        float scale = total > maxHeight ? (float) maxHeight / (float) total : 1.0f;
        int nw = Math.max(1, Math.round(width * scale));
        int nh = Math.max(1, Math.round(total * scale));
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(scale, scale);
        float y = 0f;
        for (Bitmap p : pages) {
            if (p == null || p.isRecycled()) {
                continue;
            }
            canvas.drawBitmap(p, 0f, y, null);
            y += p.getHeight();
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* config + helpers                                                    */
    /* ------------------------------------------------------------------ */

    private static JSONObject cfg() {
        synchronized (CFG_LOCK) {
            long now = System.currentTimeMillis();
            if (sCfg != null && (now - sCfgAt) < 1200L) {
                return sCfg;
            }
            sCfg = loadConfig();
            sCfgAt = now;
            return sCfg;
        }
    }

    private static JSONObject loadConfig() {
        JSONObject def = Config.defaults();

        Context ctx = currentContext();
        if (ctx != null) {
            try {
                Cursor c = ctx.getContentResolver()
                        .query(Uri.parse("content://" + Config.AUTHORITY), null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            String s = c.getString(0);
                            if (s != null && s.length() > 0) {
                                return merge(def, new JSONObject(s));
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Throwable t) {
                log("provider: " + t);
            }
        }

        try {
            File f = new File(Config.FILE_FALLBACK);
            if (f.exists()) {
                String s = readText(f);
                if (s != null && s.length() > 0) {
                    return merge(def, new JSONObject(s));
                }
            }
        } catch (Throwable t) {
            log("file: " + t);
        }
        return def;
    }

    private static JSONObject merge(JSONObject def, JSONObject src) {
        try {
            Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                def.put(k, src.get(k));
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static Context currentContext() {
        Context c = sContext;
        if (c != null) {
            return c;
        }
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                c = app.getApplicationContext();
                sContext = c;
                return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int resolveLimit(JSONObject cfg) {
        String mode = cfg.optString(Config.K_MODE, Config.MODE_SCREENS);
        if (Config.MODE_UNLIMITED.equals(mode)) {
            return -1;
        }
        if (Config.MODE_PX.equals(mode)) {
            int px = cfg.optInt(Config.K_PX, 30000);
            return px <= 0 ? -1 : px;
        }
        int screens = cfg.optInt(Config.K_SCREENS, 12);
        if (screens <= 0) {
            screens = 1;
        }
        int base = sOriginalMax > 0 ? sOriginalMax / 8 : screenHeightGuess();
        return base * screens;
    }

    private static int screenHeightGuess() {
        try {
            Context ctx = currentContext();
            if (ctx != null) {
                return ctx.getResources().getDisplayMetrics().heightPixels;
            }
        } catch (Throwable ignored) {
        }
        return 2500;
    }

    private static Field uniqueIntField(Class<?> c) {
        Field found = null;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = f;
        }
        return found;
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            log("class not found: " + name);
            return null;
        }
    }

    private static String readText(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
