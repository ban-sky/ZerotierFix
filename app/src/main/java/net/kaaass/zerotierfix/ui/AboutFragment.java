package net.kaaass.zerotierfix.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import net.kaaass.zerotierfix.BuildConfig;
import net.kaaass.zerotierfix.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
 * 关于页：版本信息、项目地址、检查更新，并支持在应用内直接下载并安装新版本。
 *
 * <p>更新流程：GitHub latest release 接口 → 取 APK 资源地址 → 下载到 cache/updates/
 * → 校验安装包签名与本应用一致 → 通过 FileProvider 交给系统安装器。Android 8.0+
 * 需要「安装未知应用」授权，未授权时会跳系统设置并在返回后自动继续安装。
 */
public class AboutFragment extends Fragment {
    private static final String PROJECT_URL = "https://github.com/ban-sky/ZerotierFix";
    private static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/ban-sky/ZerotierFix/releases/latest";
    private static final int MAX_NOTES_LENGTH = 700;
    private static final String UPDATE_DIR = "updates";

    private TextView statusView;
    private TextView latestView;
    private TextView notesView;
    private MaterialButton checkButton;
    private MaterialButton downloadButton;
    private MaterialButton openReleaseButton;

    private View downloadPanel;
    private LinearProgressIndicator progressBar;
    private TextView downloadStatus;
    private MaterialButton cancelButton;

    private boolean checkStarted;
    private boolean downloading;
    private volatile boolean cancelRequested;
    private Thread downloadThread;

    private String latestApkUrl;
    private String latestApkName;
    private File pendingInstallFile;

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

        downloadPanel = view.findViewById(R.id.about_download_panel);
        progressBar = view.findViewById(R.id.about_download_progress);
        downloadStatus = view.findViewById(R.id.about_download_status);
        cancelButton = view.findViewById(R.id.about_cancel_download);

        checkButton.setOnClickListener(v -> checkForUpdates());
        downloadButton.setOnClickListener(v -> startDownload());
        cancelButton.setOnClickListener(v -> cancelDownload());
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

    @Override
    public void onResume() {
        super.onResume();
        // 用户从「安装未知应用」设置页返回后，如果已授权就继续安装。
        if (pendingInstallFile != null && isInstallAllowed()) {
            File file = pendingInstallFile;
            pendingInstallFile = null;
            installApk(file);
        }
    }

    @Override
    public void onDestroyView() {
        cancelDownload();
        super.onDestroyView();
    }

    private void checkForUpdates() {
        if (downloading) {
            return;
        }
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
        HttpURLConnection connection =
                (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
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
            if (code == 403 || code == 429) {
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

        latestApkUrl = result.apkUrl;
        latestApkName = result.apkName;

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

        if (!TextUtils.isEmpty(result.apkUrl)) {
            downloadButton.setText(newer
                    ? R.string.about_install_update
                    : R.string.about_reinstall);
            downloadButton.setVisibility(View.VISIBLE);
        } else {
            downloadButton.setVisibility(View.GONE);
        }

        openReleaseButton.setTag(result.releaseUrl);
        openReleaseButton.setVisibility(View.VISIBLE);
    }

    private void startDownload() {
        if (downloading || TextUtils.isEmpty(latestApkUrl)) {
            return;
        }
        if (!isAdded() || getContext() == null) {
            return;
        }
        downloading = true;
        cancelRequested = false;
        checkButton.setEnabled(false);
        downloadButton.setVisibility(View.GONE);
        downloadPanel.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        downloadStatus.setText(R.string.about_download_starting);

        Context context = requireContext().getApplicationContext();
        String url = latestApkUrl;
        String fileName = safeFileName(latestApkName);

        downloadThread = new Thread(() -> {
            File downloaded = null;
            String error = null;
            try {
                downloaded = downloadApk(context, url, fileName);
            } catch (IOException e) {
                error = fileErrorMessage(e);
            }
            File finalFile = downloaded;
            String finalError = error;
            if (finalFile != null && !verifySigner(context, finalFile)) {
                // 签名对不上就不是给这个应用准备的包，删掉更安全。
                //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
                finalFile = null;
                finalError = getString(R.string.about_signature_mismatch);
            }
            File resultFile = finalFile;
            String resultError = finalError;
            postToUi(() -> onDownloadFinished(resultFile, resultError));
        }, "about-update-download");
        downloadThread.start();
    }

    private File downloadApk(Context context, String url, String fileName) throws IOException {
        File dir = new File(context.getCacheDir(), UPDATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        clearOldDownloads(dir);
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".part");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "ZerotierFix/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", "application/octet-stream");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long total = connection.getContentLength();
            postToUi(() -> {
                progressBar.setIndeterminate(total <= 0);
                if (total > 0) {
                    progressBar.setMax(100);
                }
                downloadStatus.setText(getString(R.string.about_download_starting));
            });

            byte[] buffer = new byte[16 * 1024];
            long written = 0;
            int lastPercent = -1;
            try (InputStream raw = connection.getInputStream();
                 BufferedInputStream input = new BufferedInputStream(raw);
                 FileOutputStream output = new FileOutputStream(temp)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelRequested) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    if (total > 0) {
                        int percent = (int) Math.min(100, written * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            long finalWritten = written;
                            int finalPercent = percent;
                            postToUi(() -> {
                                progressBar.setProgress(finalPercent);
                                downloadStatus.setText(getString(R.string.about_download_progress,
                                        finalPercent, formatSize(finalWritten),
                                        formatSize(total)));
                            });
                        }
                    }
                }
            }
            if (cancelRequested) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return null;
            }
            if (!temp.renameTo(target)) {
                throw new IOException("cannot rename download");
            }
            return target;
        } finally {
            connection.disconnect();
        }
    }

    private void onDownloadFinished(@Nullable File file, @Nullable String error) {
        downloading = false;
        cancelRequested = false;
        progressBar.setIndeterminate(false);
        downloadPanel.setVisibility(View.GONE);
        checkButton.setEnabled(true);
        downloadButton.setVisibility(View.VISIBLE);

        if (error != null) {
            downloadStatus.setText(getString(R.string.about_download_failed, error));
            Toast.makeText(requireContext(), getString(R.string.about_download_failed, error),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (file == null) {
            // 用户取消
            Toast.makeText(requireContext(), R.string.about_download_cancelled,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        downloadStatus.setText(R.string.about_install_ready);
        installApk(file);
    }

    private void cancelDownload() {
        if (!downloading) {
            return;
        }
        cancelRequested = true;
        Thread thread = downloadThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean isInstallAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return requireContext().getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void installApk(File file) {
        Context context = requireContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInstallAllowed()) {
            pendingInstallFile = file;
            Toast.makeText(context, R.string.about_install_permission, Toast.LENGTH_LONG).show();
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                startActivity(settingsIntent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(context, R.string.about_install_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(context, R.string.about_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean verifySigner(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (archive == null || archive.signatures == null || archive.signatures.length == 0) {
                return false;
            }
            PackageInfo self = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            if (self.signatures == null || self.signatures.length == 0) {
                return false;
            }
            for (Signature remote : archive.signatures) {
                for (Signature local : self.signatures) {
                    if (remote.equals(local)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearOldDownloads(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String safeFileName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "zerotierfix-update.apk";
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!cleaned.endsWith(".apk")) {
            cleaned = cleaned + ".apk";
        }
        return cleaned;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String fileErrorMessage(IOException e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
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
            return iso.substring(0, 10);
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
