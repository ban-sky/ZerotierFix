package net.kaaass.zerotierfix;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.kaaass.zerotierfix.ui.NetworkListActivity;

/**
 * Shows the stack trace stored by {@link CrashReporter}. Built programmatically
 * and with a platform theme so it still works when the app resources are the
 * thing that broke.
 */
public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String trace = CrashReporter.readReport(this);
        if (trace.isEmpty()) {
            finish();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(getString(R.string.crash_report_title));
        title.setTextSize(16);
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(trace);
        body.setTextIsSelectable(true);
        body.setTextSize(11);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        Button copy = new Button(this);
        copy.setText(getString(R.string.crash_report_copy));
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", trace));
            Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        Button restart = new Button(this);
        restart.setText(getString(R.string.crash_report_restart));
        restart.setOnClickListener(view -> {
            CrashReporter.clear(this);
            ZerotierFixApplication application = (ZerotierFixApplication) getApplication();
            if (application.getDaoSession() == null) {
                // 本次启动时数据库没打开成功，进程里的 DAO 会话是空的，只能让用户重开应用
                Toast.makeText(this, R.string.crash_report_reopen, Toast.LENGTH_LONG).show();
                finishAffinity();
                return;
            }
            Intent intent = new Intent(this, NetworkListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        root.addView(restart);

        setContentView(root);
    }
}
