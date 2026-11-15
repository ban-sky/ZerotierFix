package net.kaaass.zerotierfix.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 关于页：版本信息、项目地址、检查更新。
 *
 * <p>更新检查直接读 GitHub 的 latest release 接口，展示版本号、发布时间、更新说明，
 * 并在存在 APK 资源时提供「下载更新」直达链接。
 */
public class AboutFragment extends Fragment {
    private static final String PROJECT_URL = "https://github.com/ban-sky/ZerotierFix";
    private static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/ban-sky/ZerotierFix/releases/latest";
    private static final int MAX_NOTES_LENGTH = 700;

    private TextView statusView;
    private TextView latestView;
    private TextView notesView;
    private MaterialButton checkButton;
    private MaterialButton downloadButton;
    private MaterialButton openReleaseButton;
    private boolean checkStarted;

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
        projectView.setText(PROJECT_URL);
        projectView.setOnClickListener(v -> openUrl(PROJECT_URL));

        statusView = view.findViewById(R.id.about_update_status);
        latestView = view.findViewById(R.id.about_update_latest);
        notesView = view.findViewById(R.id.about_update_notes);
        checkButton = view.findViewById(R.id.about_check_update);
        downloadButton = view.findViewById(R.id.about_download_update);
        openReleaseButton = view.findViewById(R.id.about_open_release);

        checkButton.setOnClickListener(v -> checkForUpdates());
        downloadButton.setOnClickListener(v -> {
            Object tag = downloadButton.getTag();
            if (tag instanceof String) {
                openUrl((String) tag);
            }
        });
        openReleaseButton.setOnClickListener(v -> {
            Object tag = openReleaseButton.getTag();
            openUrl(tag instanceof String ? (String) tag : RELEASES_URL);
        });

        // 进入页面自动检查一次，用户仍可手动重试。
        if (!checkStarted) {
            checkStarted = true;
            checkForUpdates();
        }
    }

    private void checkForUpdates() {
        statusView.setText(R.string.about_checking);
        checkButton.setEnabled(false);
        latestView.setVisibility(View.GONE);
        notesView.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        openReleaseButton.setVisibility(View.GONE);

        new Thread(() -> {
            UpdateResult result;
            try {
                result = queryLatestRelease();
            } catch (IOException | JSONException e) {
                result = UpdateResult.failure(describeError(e));
            }
            UpdateResult finalResult = result;
            postToUi(() -> applyResult(finalResult));
        }, "about-update-check").start();
    }

    private void postToUi(Runnable block) {
        View view = getView();
        if (view != null) {
            view.post(() -> {
                if (isAdded()) {
                    block.run();
                }
            });
        }
    }

    private UpdateResult queryLatestRelease() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent",
                    "ZerotierFix/" + BuildConfig.VERSION_NAME);

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return UpdateResult.noRelease();
            }
            if (code == 403) {
                // GitHub 对匿名请求限流，返回 403 或 429。
                return UpdateResult.failure(getString(R.string.about_rate_limited));
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return UpdateResult.failure("HTTP " + code);
            }
            return parseRelease(readBody(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private UpdateResult parseRelease(String body) throws JSONException {
        JSONObject json = new JSONObject(body);
        String tag = json.optString("tag_name", "");
        String htmlUrl = json.optString("html_url", RELEASES_URL);
        String publishedAt = json.optString("published_at", "");
        String notes = json.optString("body", "");

        String apkUrl = null;
        String apkName = null;
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (!name.endsWith(".apk") || url.isEmpty()) {
                    continue;
                }
                // 优先 release 包，其次才是 debug 包。
                boolean preferred = name.toLowerCase(Locale.ROOT).contains("release");
                if (apkUrl == null || preferred) {
                    apkUrl = url;
                    apkName = name;
                    if (preferred) {
                        break;
                    }
                }
            }
        } else {
            apkUrl = htmlUrl;
        }
        return UpdateResult.success(tag, htmlUrl, publishedAt, notes, apkUrl, apkName);
    }

    private static String readBody(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private void applyResult(UpdateResult result) {
        checkButton.setEnabled(true);

        if (result.error != null) {
            statusView.setText(getString(R.string.about_check_failed, result.error));
            return;
        }
        if (result.noRelease) {
            statusView.setText(R.string.about_no_release);
            return;
        }

        String latest = result.latestVersion == null ? "" : result.latestVersion;
        String published = formatDate(result.publishedAt);
        if (TextUtils.isEmpty(published)) {
            latestView.setText(getString(R.string.about_update_latest_nodate, latest));
        } else {
            latestView.setText(getString(R.string.about_update_latest, latest, published));
        }
        latestView.setVisibility(View.VISIBLE);

        String notes = trimNotes(result.notes);
        if (!TextUtils.isEmpty(notes)) {
            notesView.setText(notes);
            notesView.setVisibility(View.VISIBLE);
        } else {
            notesView.setVisibility(View.GONE);
        }

        boolean newer = isNewer(latest, BuildConfig.VERSION_NAME);
        statusView.setText(newer
                ? getString(R.string.about_new_version, latest)
                : getString(R.string.about_up_to_date, BuildConfig.VERSION_NAME));

        if (newer && !TextUtils.isEmpty(result.apkUrl)) {
            downloadButton.setTag(result.apkUrl);
            downloadButton.setText(TextUtils.isEmpty(result.apkName)
                    ? getString(R.string.about_download_update)
                    : result.apkName);
            downloadButton.setVisibility(View.VISIBLE);
        } else {
            downloadButton.setVisibility(View.GONE);
        }

        openReleaseButton.setTag(result.releaseUrl);
        openReleaseButton.setVisibility(View.VISIBLE);
    }

    private String trimNotes(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return null;
        }
        text = text.replaceAll("\n{3,}", "\n\n");
        if (text.length() > MAX_NOTES_LENGTH) {
            text = text.substring(0, MAX_NOTES_LENGTH).trim() + "…";
        }
        return getString(R.string.about_update_notes, text);
    }

    static String formatDate(String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = parser.parse(iso);
            if (date == null) {
                return "";
            }
            SimpleDateFormat printer = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            return printer.format(date);
        } catch (ParseException e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : "";
        }
    }

    static boolean isNewer(String remoteVersion, String localVersion) {
        if (TextUtils.isEmpty(remoteVersion) || TextUtils.isEmpty(localVersion)) {
            return false;
        }
        String[] remote = remoteVersion.replaceFirst("^[vV]", "").split("\\.");
        String[] local = localVersion.replaceFirst("^[vV]", "").split("\\.");
        int length = Math.max(remote.length, local.length);
        for (int i = 0; i < length; i++) {
            int r = parsePart(i < remote.length ? remote[i] : "0");
            int l = parsePart(i < local.length ? local[i] : "0");
            if (r != l) {
                return r > l;
            }
        }
        return false;
    }

    private static int parsePart(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (!Character.isDigit(c)) {
                break;
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String describeError(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class UpdateResult {
        final boolean noRelease;
        final String latestVersion;
        final String releaseUrl;
        final String publishedAt;
        final String notes;
        final String apkUrl;
        final String apkName;
        final String error;

        private UpdateResult(boolean noRelease, String latestVersion, String releaseUrl,
                             String publishedAt, String notes, String apkUrl, String apkName,
                             String error) {
            this.noRelease = noRelease;
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
            this.publishedAt = publishedAt;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.error = error;
        }

        static UpdateResult noRelease() {
            return new UpdateResult(true, null, RELEASES_URL, null, null, null, null, null);
        }

        static UpdateResult failure(String error) {
            return new UpdateResult(false, null, RELEASES_URL, null, null, null, null, error);
        }

        static UpdateResult success(String tag, String htmlUrl, String publishedAt,
                                    String notes, String apkUrl, String apkName) {
            String version = tag == null ? "" : tag.replaceFirst("^[vV]", "");
            return new UpdateResult(false, version, htmlUrl, publishedAt, notes, apkUrl, apkName,
                    null);
        }
    }
}
