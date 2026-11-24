package io.github.mangi.etagesture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;

/** 模块设置页：手势开关、震动反馈与隐藏桌面图标。 */
public final class SettingsActivity extends Activity implements App.ServiceListener {
    private static final int COLOR_CONNECTED = 0xFF1B7F3B;
    private static final int COLOR_DISCONNECTED = 0xFFB3261E;

    private static final String UI_PREFS = "ui";
    private static final String KEY_HIDE_ICON = "hide_icon";
    private static final String ALIAS_SUFFIX = ".SettingsAlias";

    private Switch toggle;
    private Switch vibrateToggle;
    private Switch hideIconToggle;
    private TextView status;
    private SharedPreferences remotePreferences;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        applyStoredIconState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.addServiceListener(this);
    }

    @Override
    protected void onPause() {
        App.removeServiceListener(this);
        super.onPause();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        runOnUiThread(this::refresh);
    }

    private LinearLayout buildContent() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(36), padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWidth());

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.settings_subtitle);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(8), 0, dp(24));
        root.addView(subtitle, matchWidth());

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(20));
        root.addView(status, matchWidth());

        toggle = addSwitch(root, R.string.settings_toggle, (buttonView, isChecked) -> {
            if (!updating) {
                writeRemoteBoolean(Constants.KEY_ENABLED, isChecked);
            }
        });

        vibrateToggle = addSwitch(root, R.string.settings_vibrate, (buttonView, isChecked) -> {
            if (!updating) {
                writeRemoteBoolean(Constants.KEY_VIBRATE, isChecked);
            }
        });

        hideIconToggle = addSwitch(root, R.string.settings_hide_icon, (buttonView, isChecked) -> {
            if (updating) {
                return;
            }
            if (isChecked) {
                confirmHideIcon();
            } else {
                setIconHidden(false);
            }
        });

        TextView hideHint = new TextView(this);
        hideHint.setText(R.string.settings_hide_icon_summary);
        hideHint.setTextSize(12);
        hideHint.setPadding(0, dp(4), 0, 0);
        root.addView(hideHint, matchWidth());

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        hint.setTextSize(13);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint, matchWidth());

        return root;
    }

    private Switch addSwitch(LinearLayout root, int textRes, CompoundButtonListener listener) {
        Switch item = new Switch(this);
        item.setText(textRes);
        item.setTextSize(16);
        item.setPadding(0, dp(12), 0, dp(12));
        item.setOnCheckedChangeListener(listener::onCheckedChanged);
        root.addView(item, matchWidth());
        return item;
    }

    private void refresh() {
        XposedService service = App.service();
        SharedPreferences preferences = null;
        if (service != null) {
            try {
                preferences = service.getRemotePreferences(Constants.PREFS_GROUP);
            } catch (Throwable ignored) {
                // 服务刚绑定或数据库暂不可用时按未连接处理。
            }
        }
        remotePreferences = preferences;
        boolean connected = preferences != null;
        boolean enabled = !connected || preferences.getBoolean(Constants.KEY_ENABLED, true);
        boolean vibrate = !connected || preferences.getBoolean(Constants.KEY_VIBRATE, true);
        updating = true;
        toggle.setEnabled(connected);
        toggle.setChecked(connected && enabled);
        vibrateToggle.setEnabled(connected);
        vibrateToggle.setChecked(connected && vibrate);
        updating = false;
        status.setText(connected
                ? R.string.settings_status_connected
                : R.string.settings_status_disconnected);
        status.setTextColor(connected ? COLOR_CONNECTED : COLOR_DISCONNECTED);
    }

    private void writeRemoteBoolean(String key, boolean value) {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return;
        }
        boolean committed;
        try {
            committed = preferences.edit().putBoolean(key, value).commit();
        } catch (Throwable throwable) {
            committed = false;
        }
        if (!committed) {
            Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_SHORT).show();
            refresh();
        }
    }

    private void confirmHideIcon() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hide_icon_confirm_title)
                .setMessage(R.string.hide_icon_confirm_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> setIconHidden(true))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> resetHideSwitch())
                .setOnCancelListener(dialog -> resetHideSwitch())
                .show();
    }

    private void resetHideSwitch() {
        updating = true;
        hideIconToggle.setChecked(false);
        updating = false;
    }

    private void setIconHidden(boolean hidden) {
        try {
            ComponentName alias = new ComponentName(this, getPackageName() + ALIAS_SUFFIX);
            int state = hidden
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            getPackageManager().setComponentEnabledSetting(
                    alias, state, PackageManager.DONT_KILL_APP);
            getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_HIDE_ICON, hidden)
                    .apply();
            updating = true;
            hideIconToggle.setChecked(hidden);
            updating = false;
            if (hidden) {
                Toast.makeText(this, R.string.hidden_toast, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable throwable) {
            Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_SHORT).show();
            resetHideSwitch();
        }
    }

    private void applyStoredIconState() {
        boolean hidden = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HIDE_ICON, false);
        updating = true;
        hideIconToggle.setChecked(hidden);
        updating = false;
        if (hidden) {
            try {
                ComponentName alias = new ComponentName(this, getPackageName() + ALIAS_SUFFIX);
                getPackageManager().setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (Throwable ignored) {
                // 状态同步失败时保持现有图标状态。
            }
        }
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface CompoundButtonListener {
        void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked);
    }
}
