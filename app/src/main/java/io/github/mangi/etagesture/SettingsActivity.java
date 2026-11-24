package io.github.mangi.etagesture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;

/** 模块设置页：只负责开关，写入 RemotePreferences 后 Hook 进程实时读取。 */
public final class SettingsActivity extends Activity implements App.ServiceListener {
    private static final int COLOR_CONNECTED = 0xFF1B7F3B;
    private static final int COLOR_DISCONNECTED = 0xFFB3261E;

    private Switch toggle;
    private TextView status;
    private SharedPreferences remotePreferences;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
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

        toggle = new Switch(this);
        toggle.setText(R.string.settings_toggle);
        toggle.setTextSize(16);
        toggle.setPadding(0, dp(12), 0, dp(12));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> writeEnabled(isChecked));
        root.addView(toggle, matchWidth());

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        hint.setTextSize(13);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint, matchWidth());

        return root;
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
        updating = true;
        toggle.setEnabled(connected);
        toggle.setChecked(connected && enabled);
        updating = false;
        status.setText(connected
                ? R.string.settings_status_connected
                : R.string.settings_status_disconnected);
        status.setTextColor(connected ? COLOR_CONNECTED : COLOR_DISCONNECTED);
    }

    private void writeEnabled(boolean enabled) {
        if (updating) {
            return;
        }
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return;
        }
        boolean committed;
        try {
            committed = preferences.edit()
                    .putBoolean(Constants.KEY_ENABLED, enabled)
                    .commit();
        } catch (Throwable throwable) {
            committed = false;
        }
        if (!committed) {
            Toast.makeText(this, R.string.settings_write_failed, Toast.LENGTH_SHORT).show();
            refresh();
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
}
