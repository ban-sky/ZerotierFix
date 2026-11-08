package net.kaaass.zerotierfix.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.appbar.MaterialToolbar;

import net.kaaass.zerotierfix.BuildConfig;
import net.kaaass.zerotierfix.R;

/**
 * 单片段 Activity。
 *
 * <p>统一负责：启动画面、edge-to-edge 系统栏内边距、顶部 MaterialToolbar，
 * 以及唯一的 fragment 容器。子类只需实现 {@link #createFragment()}。</p>
 */
public abstract class SingleFragmentActivity extends AppCompatActivity {
    private static final String TAG = "SingleFragmentActivity";

    public abstract Fragment createFragment();

    /**
     * 是否为首页（首页不显示返回箭头，并在标题下显示版本号）。
     */
    protected boolean isHomeActivity() {
        return this instanceof NetworkListActivity;
    }

    @Override
    public void onCreate(Bundle bundle) {
        // 启动画面主题需要在 super.onCreate 之前安装
        TypedValue splashTheme = new TypedValue();
        boolean hasSplashTheme = getTheme().resolveAttribute(R.attr.postSplashScreenTheme, splashTheme, true);
        if (hasSplashTheme) {
            SplashScreen.installSplashScreen(this);
        }

        super.onCreate(bundle);

        // Edge-to-edge：内容绘制到系统栏后面，由内边距避让
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_fragment);
        setupToolbar();
        applyWindowInsets();

        FragmentManager supportFragmentManager = getSupportFragmentManager();
        Fragment findFragmentById = supportFragmentManager.findFragmentById(R.id.fragmentContainer);
        if (findFragmentById == null) {
            Fragment createFragment = createFragment();
            setArgs(createFragment);
            supportFragmentManager.beginTransaction().add(R.id.fragmentContainer, createFragment).commit();
            return;
        }
        setArgs(findFragmentById);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() == null) {
            return;
        }
        getSupportActionBar().setDisplayShowTitleEnabled(true);
        if (isHomeActivity()) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            toolbar.setNavigationIcon(null);
            toolbar.setSubtitle("ZeroTier 1.16 \u00b7 v" + BuildConfig.VERSION_NAME);
        } else {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(view -> onSupportNavigateUp());
        }
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        View appBar = findViewById(R.id.app_bar);
        View container = findViewById(R.id.fragmentContainer);

        final int appBarPaddingLeft = appBar.getPaddingLeft();
        final int appBarPaddingRight = appBar.getPaddingRight();
        final int appBarPaddingTop = appBar.getPaddingTop();
        final int appBarPaddingBottom = appBar.getPaddingBottom();
        final int containerPaddingLeft = container.getPaddingLeft();
        final int containerPaddingTop = container.getPaddingTop();
        final int containerPaddingRight = container.getPaddingRight();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            // Android 15+ draws edge-to-edge by default and no longer resizes the
            // window for the keyboard, so the IME inset has to be consumed here;
            // otherwise the soft keyboard covers the bottom of the form.
            int topInset = Math.max(systemBars.top, cutout.top);
            int bottomInset = Math.max(systemBars.bottom, Math.max(ime.bottom, cutout.bottom));
            appBar.setPadding(appBarPaddingLeft, appBarPaddingTop + topInset,
                    appBarPaddingRight, appBarPaddingBottom);
            container.setPadding(containerPaddingLeft, containerPaddingTop,
                    containerPaddingRight, bottomInset);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);

        // 状态栏 / 导航栏图标颜色跟随深浅色主题
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void setArgs(Fragment fragment) {
        Bundle extras;
        Intent intent = getIntent();
        if (intent != null && (extras = intent.getExtras()) != null && !fragment.isAdded()) {
            try {
                fragment.setArguments(extras);
            } catch (IllegalArgumentException unused) {
                Log.e(TAG, "Exception setting arguments on fragment");
            }
        }
    }
}
