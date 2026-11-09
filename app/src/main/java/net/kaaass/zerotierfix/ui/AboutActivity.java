package net.kaaass.zerotierfix.ui;

import androidx.fragment.app.Fragment;

/**
 * 关于页容器。
 */
public class AboutActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new AboutFragment();
    }
}
