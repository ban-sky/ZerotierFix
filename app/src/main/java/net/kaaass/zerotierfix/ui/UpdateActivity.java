package net.kaaass.zerotierfix.ui;

import androidx.fragment.app.Fragment;

/**
 * 更新页容器。
 */
public class UpdateActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new UpdateFragment();
    }
}
