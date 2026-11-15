package net.kaaass.zerotierfix.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.PeerPhysicalPath;
import com.zerotier.sdk.PeerRole;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.StringUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 节点详情弹窗：展示对端设备的地址、角色、版本、延迟，以及本机观察到的全部物理路径。
 * <p>
 * 列表里的 Peer 对象只在内存中存在，这里把展示需要的字段编码进 arguments，
 * 这样弹窗在进程被回收重建后也能正常显示。
 *
 * @author ban-sky
 */
public class PeerDetailDialogFragment extends BottomSheetDialogFragment {

    public static final String TAG = "PeerDetailDialogFragment";

    private static final String ARG_ADDRESS = "peer_address";
    private static final String ARG_VERSION_MAJOR = "peer_version_major";
    private static final String ARG_VERSION_MINOR = "peer_version_minor";
    private static final String ARG_VERSION_REV = "peer_version_rev";
    private static final String ARG_LATENCY = "peer_latency";
    private static final String ARG_ROLE = "peer_role";
    private static final String ARG_PATHS = "peer_paths";

    /**
     * 路径编码分隔符，不会出现在 IP:Port 文本里
     */
    private static final String PATH_SEPARATOR = "\u0001";

    public static PeerDetailDialogFragment newInstance(Peer peer) {
        Bundle args = new Bundle();
        args.putLong(ARG_ADDRESS, peer.getAddress());
        args.putInt(ARG_VERSION_MAJOR, peer.getVersionMajor());
        args.putInt(ARG_VERSION_MINOR, peer.getVersionMinor());
        args.putInt(ARG_VERSION_REV, peer.getVersionRev());
        args.putInt(ARG_LATENCY, peer.getLatency());
        PeerRole role = peer.getRole();
        args.putString(ARG_ROLE, role == null ? null : role.name());
        args.putStringArrayList(ARG_PATHS, encodePaths(peer.getPaths()));
        PeerDetailDialogFragment fragment = new PeerDetailDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private static ArrayList<String> encodePaths(PeerPhysicalPath[] paths) {
        ArrayList<String> result = new ArrayList<>();
        if (paths == null) {
            return result;
        }
        for (PeerPhysicalPath path : paths) {
            if (path == null) {
                continue;
            }
            InetSocketAddress address = path.getAddress();
            String addressText = "";
            if (address != null && address.getAddress() != null) {
                addressText = StringUtils.toString(address);
            }
            result.add(addressText
                    + PATH_SEPARATOR + path.getLastSend()
                    + PATH_SEPARATOR + path.getLastReceive()
                    + PATH_SEPARATOR + path.isPreferred());
        }
        return result;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_peer_detail, container, false);
        Bundle args = requireArguments();

        // 节点 ID
        long address = args.getLong(ARG_ADDRESS);
        TextView addressView = view.findViewById(R.id.detail_peer_addr);
        addressView.setText(String.format(Locale.ROOT, "%010x", address));

        // 角色
        TextView roleView = view.findViewById(R.id.detail_peer_role);
        String roleName = args.getString(ARG_ROLE);
        if (roleName == null) {
            roleView.setVisibility(View.GONE);
        } else {
            int roleRes = PeerListFragment.peerRoleToString(PeerRole.valueOf(roleName));
            if (roleRes == 0) {
                roleView.setVisibility(View.GONE);
            } else {
                roleView.setText(roleRes);
            }
        }

        // 版本
        TextView versionView = view.findViewById(R.id.detail_peer_version);
        int versionMajor = args.getInt(ARG_VERSION_MAJOR);
        if (versionMajor > 0) {
            versionView.setText(String.format(Locale.ROOT, "%d.%d.%d", versionMajor,
                    args.getInt(ARG_VERSION_MINOR), args.getInt(ARG_VERSION_REV)));
        } else {
            versionView.setText(R.string.unknown_version);
        }

        // 延迟
        TextView latencyView = view.findViewById(R.id.detail_peer_latency);
        latencyView.setText(getString(R.string.peer_lat, args.getInt(ARG_LATENCY)));

        // 物理路径
        List<String> encodedPaths = args.getStringArrayList(ARG_PATHS);
        LinearLayout pathsContainer = view.findViewById(R.id.detail_peer_paths);
        TextView noPathsView = view.findViewById(R.id.detail_peer_no_paths);
        TextView pathCountView = view.findViewById(R.id.detail_peer_path_count);
        int pathCount = encodedPaths == null ? 0 : encodedPaths.size();
        pathCountView.setText(getString(R.string.peer_path_count, pathCount));
        if (pathCount == 0) {
            noPathsView.setVisibility(View.VISIBLE);
        } else {
            for (String encoded : encodedPaths) {
                bindPath(inflater, pathsContainer, encoded);
            }
        }

        // 复制节点 ID
        com.google.android.material.button.MaterialButton copyButton =
                view.findViewById(R.id.detail_peer_copy);
        String nodeId = addressView.getText().toString();
        copyButton.setOnClickListener(v -> {
            Context context = requireContext();
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("node id", nodeId));
                Toast.makeText(context, R.string.peer_id_copied, Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    private void bindPath(LayoutInflater inflater, LinearLayout container, String encoded) {
        String[] parts = encoded.split(PATH_SEPARATOR, -1);
        if (parts.length < 4) {
            return;
        }
        View item = inflater.inflate(R.layout.list_item_peer_path, container, false);
        TextView addressView = item.findViewById(R.id.peer_path_address);
        String address = parts[0];
        addressView.setText(address.isEmpty() ? getString(R.string.peer_path_unknown_address) : address);
        TextView preferredView = item.findViewById(R.id.peer_path_preferred);
        preferredView.setVisibility(Boolean.parseBoolean(parts[3]) ? View.VISIBLE : View.GONE);
        TextView timesView = item.findViewById(R.id.peer_path_times);
        long lastSend = parseLong(parts[1]);
        long lastReceive = parseLong(parts[2]);
        timesView.setText(getString(R.string.peer_path_last_send, relativeTime(lastSend))
                + " · " + getString(R.string.peer_path_last_receive, relativeTime(lastReceive)));
        container.addView(item);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 把时间戳转为「x 秒前」这类相对时间
     */
    private String relativeTime(long timestamp) {
        if (timestamp <= 0) {
            return getString(R.string.peer_path_never);
        }
        long diff = Math.max(0L, System.currentTimeMillis() - timestamp);
        if (diff < 60_000L) {
            return getString(R.string.peer_time_seconds, diff / 1000L);
        }
        if (diff < 3_600_000L) {
            return getString(R.string.peer_time_minutes, diff / 60_000L);
        }
        if (diff < 86_400_000L) {
            return getString(R.string.peer_time_hours, diff / 3_600_000L);
        }
        return getString(R.string.peer_time_days, diff / 86_400_000L);
    }
}
