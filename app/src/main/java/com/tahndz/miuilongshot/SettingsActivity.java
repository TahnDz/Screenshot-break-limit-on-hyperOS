package com.tahndz.miuilongshot;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class SettingsActivity extends Activity {

    private Switch swEnabled;
    private Switch swDownscale;
    private RadioGroup rgMode;
    private RadioButton rbScreens;
    private RadioButton rbPx;
    private RadioButton rbUnlimited;

    private EditText etScreens;
    private EditText etPx;
    private EditText etScroll;
    private EditText etDelay;
    private EditText etSwipe;
    private EditText etFling;
    private EditText etMaxBmp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));

        JSONObject cfg = read();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        /* enabled */
        swEnabled = new Switch(this);
        swEnabled.setText(R.string.lbl_enabled);
        swEnabled.setChecked(cfg.optBoolean(Config.K_ENABLED, true));
        root.addView(swEnabled);

        root.addView(title(R.string.lbl_limit_mode));

        rgMode = new RadioGroup(this);
        rbScreens = radio(rgMode, R.string.mode_screens, Config.MODE_SCREENS.equals(cfg.optString(Config.K_MODE, Config.MODE_SCREENS)));
        rbPx = radio(rgMode, R.string.mode_px, Config.MODE_PX.equals(cfg.optString(Config.K_MODE, Config.MODE_SCREENS)));
        rbUnlimited = radio(rgMode, R.string.mode_unlimited, Config.MODE_UNLIMITED.equals(cfg.optString(Config.K_MODE, Config.MODE_SCREENS)));
        root.addView(rgMode);

        etScreens = numField(root, R.string.lbl_screens, cfg.optInt(Config.K_SCREENS, 12));
        etPx = numField(root, R.string.lbl_px, cfg.optInt(Config.K_PX, 30000));
        etScroll = numField(root, R.string.lbl_scroll_ms, cfg.optInt(Config.K_SCROLL_MS, 120));
        etDelay = numField(root, R.string.lbl_delay_ms, cfg.optInt(Config.K_DELAY_MS, 180));
        etSwipe = decField(root, R.string.lbl_swipe, cfg.optDouble(Config.K_SWIPE, 1.0d));
        etFling = decField(root, R.string.lbl_fling, cfg.optDouble(Config.K_FLING, 2.0d));
        etMaxBmp = numField(root, R.string.lbl_max_bmp, cfg.optInt(Config.K_MAX_BMP, 30000));

        swDownscale = new Switch(this);
        swDownscale.setText(R.string.lbl_downscale);
        swDownscale.setChecked(cfg.optBoolean(Config.K_DOWNSCALE, true));
        root.addView(swDownscale);

        Button save = new Button(this);
        save.setText(R.string.btn_save);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(save, lp);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint_restart);
        hint.setTextSize(13f);
        hint.setTextColor(0xFF888888);
        root.addView(hint, lp);
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put(Config.K_ENABLED, swEnabled.isChecked());
            String mode = Config.MODE_SCREENS;
            if (rbPx.isChecked()) {
                mode = Config.MODE_PX;
            } else if (rbUnlimited.isChecked()) {
                mode = Config.MODE_UNLIMITED;
            }
            o.put(Config.K_MODE, mode);
            o.put(Config.K_SCREENS, clamp(intOf(etScreens, 12), 1, 16));
            o.put(Config.K_PX, clamp(intOf(etPx, 30000), 1000, 1000000));
            o.put(Config.K_SCROLL_MS, clamp(intOf(etScroll, 120), 30, 1000));
            o.put(Config.K_DELAY_MS, clamp(intOf(etDelay, 180), 0, 2000));
            o.put(Config.K_SWIPE, clamp(dblOf(etSwipe, 1.0d), 0.5d, 2.0d));
            o.put(Config.K_FLING, clamp(dblOf(etFling, 2.0d), 1.0d, 10.0d));
            o.put(Config.K_DOWNSCALE, swDownscale.isChecked());
            o.put(Config.K_MAX_BMP, clamp(intOf(etMaxBmp, 30000), 1000, 32000));
            Config.writeStored(this, o.toString());
            Toast.makeText(this, R.string.msg_saved, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t), Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject read() {
        try {
            return new JSONObject(Config.readStored(this));
        } catch (Throwable t) {
            return Config.defaults();
        }
    }

    private TextView title(int res) {
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(20), 0, dp(6));
        return tv;
    }

    private RadioButton radio(RadioGroup group, int res, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setText(res);
        rb.setChecked(checked);
        group.addView(rb);
        return rb;
    }

    private EditText numField(LinearLayout root, int res, int value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTextSize(14f);
        box.addView(tv);
        EditText et = new EditText(this);
        et.setText(String.valueOf(value));
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(box, matchWrap());
        return et;
    }

    private EditText decField(LinearLayout root, int res, double value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTextSize(14f);
        box.addView(tv);
        EditText et = new EditText(this);
        et.setText(String.valueOf(value));
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setSingleLine(true);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(box, matchWrap());
        return et;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int intOf(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double dblOf(EditText et, double def) {
        try {
            return Double.parseDouble(et.getText().toString().trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
