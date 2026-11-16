package net.kaaass.zerotierfix.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import net.kaaass.zerotierfix.BuildConfig;
import net.kaaass.zerotierfix.R;

/**
 * 关于页：只展示版本、项目地址与许可证，更新相关操作统一放到 {@link UpdateActivity}。
 */
public class AboutFragment extends Fragment {
    private static final String PROJECT_URL = "https://github.com/ban-sky/ZerotierFix";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView versionView = view.findViewById(R.id.about_version);
        versionView.setText(getString(R.string.about_version_format,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        TextView projectView = view.findViewById(R.id.about_project_url);
        projectView.setOnClickListener(v -> openProjectPage());

        MaterialButton updateButton = view.findViewById(R.id.about_open_update);
        updateButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), UpdateActivity.class)));
    }

    private void openProjectPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
