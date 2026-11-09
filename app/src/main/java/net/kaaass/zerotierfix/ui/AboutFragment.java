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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 关于页：版本信息、项目地址、检查更新。
 */
public class AboutFragment extends Fragment {
    private static final String PROJECT_URL = "https://github.com/ban-sky/ZerotierFix";
    private static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/ban-sky/ZerotierFix/releases/latest";

    private TextView statusView;
    private MaterialButton checkButton;
    private MaterialButton releaseButton;
    private String releaseUrl = RELEASES_URL;

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
        this.statusView = view.findViewById(R.id.about_update_status);
        this.checkButton = view.findViewById(R.id.about_check_update);
        this.releaseButton = view.findViewById(R.id.about_open_release);
        this.checkButton.setOnClickListener(v -> checkForUpdates());
        this.releaseButton.setOnClickListener(v -> openUrl(this.releaseUrl));
    }

    private void checkForUpdates() {
        this.checkButton.setEnabled(false);
        this.statusView.setText(R.string.about_checking);
        this.releaseButton.setVisibility(View.GONE);

        final android.app.Activity activity = requireActivity();
        new Thread(() -> {
            final UpdateResult result;
            try {
                JSONObject release = fetchLatestRelease();
                if (release == null) {
                    result = UpdateResult.noRelease();
                } else {
                    result = UpdateResult.of(
                            release.optString("tag_name", ""),
                            release.optString("html_url", RELEASES_URL));
                }
            } catch (Exception e) {
                result = UpdateResult.error(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
            activity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                applyResult(result);
            });
        }, "about-update-check").start();
    }

    private void applyResult(UpdateResult result) {
        this.checkButton.setEnabled(true);
        if (result.error != null) {
            this.statusView.setText(getString(R.string.about_check_failed, result.error));
            this.releaseButton.setVisibility(View.GONE);
            return;
        }
        if (result.noRelease) {
            this.releaseUrl = RELEASES_URL;
            this.statusView.setText(R.string.about_no_release);
            this.releaseButton.setVisibility(View.VISIBLE);
            return;
        }
        this.releaseUrl = result.releaseUrl;
        if (isNewer(result.latestVersion, BuildConfig.VERSION_NAME)) {
            this.statusView.setText(getString(R.string.about_new_version, result.latestVersion));
            this.releaseButton.setVisibility(View.VISIBLE);
        } else {
            this.statusView.setText(getString(R.string.about_up_to_date, BuildConfig.VERSION_NAME));
            this.releaseButton.setVisibility(View.GONE);
        }
    }

    private JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "ZerotierFix/" + BuildConfig.VERSION_NAME);
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                return new JSONObject(body.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 比较形如 1.2.3 的版本号，latest 更大返回 true。
     */
    private static boolean isNewer(String latest, String current) {
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int left = i < latestParts.length ? parsePart(latestParts[i]) : 0;
            int right = i < currentParts.length ? parsePart(currentParts[i]) : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int parsePart(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class UpdateResult {
        final boolean noRelease;
        final String latestVersion;
        final String releaseUrl;
        final String error;

        private UpdateResult(boolean noRelease, String latestVersion, String releaseUrl, String error) {
            this.noRelease = noRelease;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.error = error;
        }

        static UpdateResult noRelease() {
            return new UpdateResult(true, "", RELEASES_URL, null);
        }

        static UpdateResult of(String tag, String htmlUrl) {
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            return new UpdateResult(false, version, htmlUrl, null);
        }

        static UpdateResult error(String message) {
            return new UpdateResult(false, "", RELEASES_URL, message);
        }
    }
}
