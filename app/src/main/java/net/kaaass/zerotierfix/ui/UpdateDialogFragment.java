package net.kaaass.zerotierfix.ui;

import android.app.Dialog;
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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 更新页：检查 GitHub Releases、展示新版本与更新说明，并在应用内下载、校验、安装。
 *
 * <p>为避免页面被销毁后后台线程再回调导致
 * {@code IllegalStateException: Fragment ... not attached to a context}，
 * 这里约定：后台线程只处理数据，绝不调用 {@code getString}/{@code getResources}；
 * 所有文案都在主线程按资源 id 组装，并且回调前统一检查 {@link #isAdded()}。</p>
 */
public class UpdateDialogFragment extends BottomSheetDialogFragment {
    private static final String TAG = "UpdateDialog";
    private static final String PROJECT_URL = "https://github.com/ban-sky/ZerotierFix";
    private static final String RELEASES_URL = PROJECT_URL + "/releases";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/ban-sky/ZerotierFix/releases/latest";
    /**
     * 国内直连 GitHub API 经常被墙或超时，按顺序尝试公共镜像（前缀 + 原始地址）。
     * 最后一项为空串，表示直连。
     */
    private static final String[] MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            ""
    };
    /**
     * 全部镜像都不可用时的兜底：仓库内维护的最新版本信息，由 jsDelivr 提供，
     * 国内可以直接访问。字段见 docs/latest.json。
     */
    private static final String CDN_LATEST_JSON =
            "https://cdn.jsdelivr.net/gh/ban-sky/ZerotierFix@main/docs/latest.json";
    private static final int MAX_NOTES_LENGTH = 700;
    private static final String UPDATE_DIR = "updates";
    private static final String DIALOG_TAG = "update-dialog";

    private final Map<String, String> changelog = new LinkedHashMap<>();
    private String newestChangelogVersion;

    private TextView versionView;
    private TextView statusView;

    private View latestCard;
    private TextView latestVersionView;
    private TextView latestDateView;
    private TextView notesView;

    private MaterialButton checkButton;
    private MaterialButton downloadButton;

    private View hintCard;
    private MaterialButton retryButton;
    private MaterialButton openReleaseButton;

    private View downloadPanel;
    private LinearProgressIndicator progressBar;
    private TextView downloadStatus;
    private MaterialButton cancelButton;

    private boolean downloading;
    private volatile boolean cancelRequested;
    private Thread downloadThread;

    private String latestApkUrl;
    /** 上一次成功访问 GitHub 用的镜像前缀，下载时优先复用。 */
    private String downloadPrefix = "";
    private String latestApkName;
    private File pendingInstallFile;

    public static void show(@NonNull FragmentManager manager) {
        UpdateDialogFragment fragment = new UpdateDialogFragment();
        fragment.show(manager, DIALOG_TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new BottomSheetDialog(requireContext(), getTheme());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_update, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) dialog).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        versionView = view.findViewById(R.id.update_current_version);
        versionView.setText(getString(R.string.about_version_format,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        statusView = view.findViewById(R.id.update_status);
        latestCard = view.findViewById(R.id.update_latest_card);
        latestVersionView = view.findViewById(R.id.update_latest_version);
        latestDateView = view.findViewById(R.id.update_latest_date);
        notesView = view.findViewById(R.id.update_notes);
        checkButton = view.findViewById(R.id.update_check);
        downloadButton = view.findViewById(R.id.update_download);
        hintCard = view.findViewById(R.id.update_hint_card);
        retryButton = view.findViewById(R.id.update_retry);
        openReleaseButton = view.findViewById(R.id.update_open_release);

        downloadPanel = view.findViewById(R.id.update_download_panel);
        progressBar = view.findViewById(R.id.update_download_progress);
        downloadStatus = view.findViewById(R.id.update_download_status);
        cancelButton = view.findViewById(R.id.update_cancel_download);

        checkButton.setOnClickListener(v -> checkForUpdates());
        retryButton.setOnClickListener(v -> checkForUpdates());
        downloadButton.setOnClickListener(v -> startDownload());
        cancelButton.setOnClickListener(v -> cancelDownload());
        openReleaseButton.setOnClickListener(v -> openUrl(RELEASES_URL));
        View closeButton = view.findViewById(R.id.update_close);
        closeButton.setOnClickListener(v -> dismiss());

        loadChangelog();
        showLocalChangelog();
        checkForUpdates();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 用户从「安装未知应用」设置页返回后，如果已授权就继续安装。
        if (pendingInstallFile != null && isAdded() && isInstallAllowed()) {
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
        if (downloading || !isAdded()) {
            return;
        }
        statusView.setText(R.string.about_checking);
        checkButton.setEnabled(false);
        downloadButton.setVisibility(View.GONE);
        hintCard.setVisibility(View.GONE);

        new Thread(() -> {
            CheckResult result;
            try {
                result = queryLatestRelease();
            } catch (IOException e) {
                result = CheckResult.networkError(describe(e));
            } catch (JSONException e) {
                result = CheckResult.networkError(describe(e));
            }
            CheckResult finalResult = result;
            postToUi(() -> applyResult(finalResult));
        }, "update-check").start();
    }

    private void postToUi(Runnable block) {
        View view = getView();
        if (view == null) {
            return;
        }
        view.post(() -> {
            if (isAdded() && getView() != null) {
                block.run();
            }
        });
    }

    /**
     * 依次尝试各个镜像与直连，任何一个返回合法 JSON 就采用；全部失败再读 CDN 兜底文件。
     * 后台线程，禁止取字符串资源。
     */
    private CheckResult queryLatestRelease() throws IOException, JSONException {
        IOException lastError = null;
        for (String prefix : MIRROR_PREFIXES) {
            try {
                CheckResult result = queryReleaseFrom(prefix);
                if (result != null) {
                    downloadPrefix = prefix;
                    return result;
                }
            } catch (IOException e) {
                lastError = e;
            } catch (JSONException e) {
                // 镜像返回了 HTML 错误页，换下一个源。
                lastError = new IOException("bad json from " + prefix, e);
            }
        }
        try {
            return queryCdnLatest();
        } catch (IOException e) {
            if (lastError != null) {
                throw lastError;
            }
            throw e;
        }
    }

    private CheckResult queryReleaseFrom(String prefix) throws IOException, JSONException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(prefix + LATEST_RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent",
                    "ZerotierFix/" + BuildConfig.VERSION_NAME);

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return CheckResult.noRelease();
            }
            if (code == 403 || code == 429) {
                // GitHub 对匿名请求限流，返回 403 或 429。注意这里不能取字符串资源。
                return CheckResult.rateLimited();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            return parseRelease(readBody(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * CDN 兜底文件由 jsDelivr 提供，国内可直连；内容是 GitHub Release 的简化快照。
     */
    private CheckResult queryCdnLatest() throws IOException, JSONException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(CDN_LATEST_JSON).openConnection();
        try {
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "ZerotierFix/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            JSONObject json = new JSONObject(readBody(connection.getInputStream()));
            String version = json.optString("version", "");
            if (version.isEmpty()) {
                throw new IOException("empty cdn payload");
            }
            String releaseUrl = json.optString("releaseUrl", RELEASES_URL);
            String publishedAt = json.optString("publishedAt", "");
            String notes = json.optString("notes", "");
            String apkUrl = json.optString("apkUrl", "");
            String apkName = json.optString("apkName", "");
            downloadPrefix = "";
            return CheckResult.success(version, releaseUrl, publishedAt, notes,
                    apkUrl.isEmpty() ? null : apkUrl,
                    apkName.isEmpty() ? null : apkName);
        } finally {
            connection.disconnect();
        }
    }

    private CheckResult parseRelease(String body) throws JSONException {
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
        return CheckResult.success(tag, htmlUrl, publishedAt, notes, apkUrl, apkName);
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

    private void applyResult(CheckResult result) {
        checkButton.setEnabled(true);

        if (result.errorRes != 0) {
            statusView.setText(result.errorDetail == null
                    ? getString(result.errorRes)
                    : getString(result.errorRes, result.errorDetail));
            hintCard.setVisibility(View.VISIBLE);
            return;
        }
        if (result.noRelease) {
            statusView.setText(R.string.about_no_release);
            hintCard.setVisibility(View.VISIBLE);
            return;
        }

        latestApkUrl = result.apkUrl;
        latestApkName = result.apkName;
        hintCard.setVisibility(View.GONE);

        String latest = result.version == null ? "" : result.version;
        String published = formatDate(result.publishedAt);
        latestVersionView.setText(getString(R.string.update_latest_version, latest));
        if (TextUtils.isEmpty(published)) {
            latestDateView.setVisibility(View.GONE);
        } else {
            latestDateView.setText(getString(R.string.update_published_at, published));
            latestDateView.setVisibility(View.VISIBLE);
        }

        String localNotes = changelog.get(latest);
        String notes = localNotes != null
                ? getString(R.string.about_update_notes, localNotes)
                : trimNotes(result.notes);
        if (!TextUtils.isEmpty(notes)) {
            notesView.setText(notes);
            notesView.setVisibility(View.VISIBLE);
        } else {
            notesView.setVisibility(View.GONE);
        }
        latestCard.setVisibility(View.VISIBLE);

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
    }

    private void startDownload() {
        if (downloading || TextUtils.isEmpty(latestApkUrl) || !isAdded()) {
            return;
        }
        downloading = true;
        cancelRequested = false;
        checkButton.setEnabled(false);
        downloadButton.setVisibility(View.GONE);
        hintCard.setVisibility(View.GONE);
        downloadPanel.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        downloadStatus.setText(R.string.about_download_starting);

        Context appContext = requireContext().getApplicationContext();
        String url = latestApkUrl;
        String fileName = safeFileName(latestApkName);

        downloadThread = new Thread(() -> {
            File downloaded = null;
            String error = null;
            try {
                downloaded = downloadApk(appContext, url, fileName);
            } catch (IOException e) {
                error = describe(e);
            }
            File finalFile = downloaded;
            String finalError = error;
            if (finalFile != null && !verifySigner(appContext, finalFile)) {
                // 签名对不上就不是给这个应用准备的包，删掉更安全。
                //noinspection ResultOfMethodCallIgnored
                finalFile.delete();
                finalFile = null;
                finalError = SIGNATURE_MISMATCH;
            }
            File resultFile = finalFile;
            String resultError = finalError;
            postToUi(() -> onDownloadFinished(resultFile, resultError));
        }, "update-download");
        downloadThread.start();
    }

    private static final String SIGNATURE_MISMATCH = "signature-mismatch";

    private File downloadApk(Context context, String url, String fileName) throws IOException {
        File dir = new File(context.getCacheDir(), UPDATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        clearOldDownloads(dir);
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".part");

        IOException lastError = null;
        for (String prefix : downloadSources()) {
            if (cancelRequested) {
                return null;
            }
            try {
                File result = downloadOnce(prefix + url, temp, target);
                if (result != null) {
                    return result;
                }
                if (cancelRequested) {
                    return null;
                }
            } catch (IOException e) {
                lastError = e;
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
        throw lastError != null ? lastError : new IOException("no download source");
    }

    /**
     * 优先复用刚才检查更新成功的镜像，再依次尝试其它镜像与直连。
     */
    private List<String> downloadSources() {
        List<String> sources = new ArrayList<>();
        if (downloadPrefix != null && !downloadPrefix.isEmpty()) {
            sources.add(downloadPrefix);
        }
        for (String prefix : MIRROR_PREFIXES) {
            if (!sources.contains(prefix)) {
                sources.add(prefix);
            }
        }
        return sources;
    }

    /**
     * 下载单个候选地址；HTTP 非 200 抛异常由上层换源，取消返回 null。
     */
    private File downloadOnce(String url, File temp, File target) throws IOException {
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
                    progressBar.setProgress(0);
                }
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
        checkButton.setEnabled(true);
        downloadButton.setVisibility(View.VISIBLE);

        if (error != null) {
            // 失败时保留下载面板，把原因留在界面上。
            downloadPanel.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            String message = SIGNATURE_MISMATCH.equals(error)
                    ? getString(R.string.about_signature_mismatch)
                    : getString(R.string.about_download_failed, error);
            downloadStatus.setText(message);
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            return;
        }
        downloadPanel.setVisibility(View.GONE);
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

    private static void clearOldDownloads(File dir) {
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

    private static String formatDate(String iso) {
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

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void openUrl(String url) {
        if (!isAdded()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 读取内置的中文更新日志，格式为「版本号 + 若干行说明」，版本从新到旧排列。
     */
    private void loadChangelog() {
        changelog.clear();
        newestChangelogVersion = null;
        try (InputStream input = getResources().openRawResource(R.raw.changelog_zh);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String version = null;
            StringBuilder body = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.matches("\\d+(\\.\\d+)+")) {
                    if (version != null) {
                        changelog.put(version, body.toString().trim());
                    }
                    version = trimmed;
                    body.setLength(0);
                } else if (version != null) {
                    if (!trimmed.isEmpty()) {
                        body.append(trimmed).append('\n');
                    }
                }
            }
            if (version != null) {
                changelog.put(version, body.toString().trim());
            }
            // 文件按「新版本在前」排列，第一个条目就是最新日志。
            if (!changelog.isEmpty()) {
                newestChangelogVersion = changelog.keySet().iterator().next();
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot read bundled changelog", e);
        }
    }

    /**
     * 网络请求还没回来时，先把内置的最新版本日志展示出来，断网时也能看到更新内容。
     */
    private void showLocalChangelog() {
        if (newestChangelogVersion == null) {
            return;
        }
        String notes = changelog.get(newestChangelogVersion);
        latestVersionView.setText(getString(R.string.update_changelog_version,
                newestChangelogVersion));
        latestDateView.setVisibility(View.GONE);
        if (notes == null || notes.isEmpty()) {
            notesView.setVisibility(View.GONE);
        } else {
            notesView.setText(getString(R.string.about_update_notes, notes));
            notesView.setVisibility(View.VISIBLE);
        }
        latestCard.setVisibility(View.VISIBLE);
    }

    private static final class CheckResult {
        final boolean noRelease;
        final int errorRes;
        final String errorDetail;
        final String version;
        final String releaseUrl;
        final String publishedAt;
        final String notes;
        final String apkUrl;
        final String apkName;

        private CheckResult(boolean noRelease, int errorRes, String errorDetail, String version,
                            String releaseUrl, String publishedAt, String notes,
                            String apkUrl, String apkName) {
            this.noRelease = noRelease;
            this.errorRes = errorRes;
            this.errorDetail = errorDetail;
            this.version = version;
            this.releaseUrl = releaseUrl;
            this.publishedAt = publishedAt;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
        }

        static CheckResult noRelease() {
            return new CheckResult(true, 0, null, null, null, null, null, null, null);
        }

        static CheckResult rateLimited() {
            return new CheckResult(false, R.string.update_rate_limited, null, null, null, null,
                    null, null, null);
        }

        static CheckResult networkError(String detail) {
            return new CheckResult(false, R.string.update_network_error, detail, null, null, null,
                    null, null, null);
        }

        static CheckResult success(String tag, String htmlUrl, String publishedAt,
                                   String notes, String apkUrl, String apkName) {
            String version = tag == null ? "" : tag.replaceFirst("^[vV]", "");
            return new CheckResult(false, 0, null, version, htmlUrl, publishedAt, notes, apkUrl,
                    apkName);
        }
    }
}
