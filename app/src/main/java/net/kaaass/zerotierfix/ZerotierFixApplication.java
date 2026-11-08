package net.kaaass.zerotierfix;

import android.content.Intent;
import android.util.Log;

import androidx.multidex.MultiDexApplication;

import net.kaaass.zerotierfix.model.DaoMaster;
import net.kaaass.zerotierfix.model.DaoSession;
import net.kaaass.zerotierfix.model.ZTOpenHelper;

/**
 * 主程序入口
 *
 * @author kaaass
 */
public class ZerotierFixApplication extends MultiDexApplication {
    private static final String TAG = "Application";

    private DaoSession mDaoSession;

    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Starting Application");
        CrashReporter.install(this);
        try {
            // 创建 DAO 会话
            this.mDaoSession = new DaoMaster(
                    new ZTOpenHelper(this, "ztfixdb", null)
                            .getWritableDatabase()
            ).newSession();
        } catch (RuntimeException | Error e) {
            // 数据库升级失败时进程通常会被系统杀掉，先把堆栈存下来，下次启动展示给用户
            Log.e(TAG, "Unable to open the database", e);
            CrashReporter.save(this, e);
        }
        if (CrashReporter.hasReport(this)) {
            try {
                Intent intent = new Intent(this, CrashReportActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to show the crash report", e);
            }
        }
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
}
