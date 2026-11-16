package net.kaaass.zerotierfix.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.PeerPhysicalPath;
import com.zerotier.sdk.PeerRole;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.events.PeerInfoReplyEvent;
import net.kaaass.zerotierfix.events.PeerInfoRequestEvent;
import net.kaaass.zerotierfix.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import lombok.ToString;

/**
 * Peer 信息展示 fragment
 *
 * @author kaaass
 */
public class PeerListFragment extends Fragment {

    public static final String TAG = "PeerListFragment";

    /**
     * 页面可见时自动查询 Peer 信息的间隔
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 3000L;

    /**
     * 手动下拉刷新等待回复的超时时间
     */
    private static final long REFRESH_TIMEOUT_MS = 4000L;

    private final List<Peer> peerList = new ArrayList<>();

    private final EventBus eventBus;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * 页面可见时周期性刷新，使延迟/路径等数据随 ZeroTier 的 ping 结果更新
     */
    private final Runnable autoRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || getView() == null) {
                return;
            }
            requestPeers(false);
            uiHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
        }
    };

    /**
     * 手动刷新的超时保护：避免 spinner 一直转
     */
    private final Runnable refreshTimeoutTask = () -> {
        if (isAdded()) {
            this.manualRefresh = false;
            setRefreshing(false);
        }
    };

    private RecyclerViewAdapter recyclerViewAdapter = null;

    private RecyclerView recyclerView = null;

    private SwipeRefreshLayout swipeRefreshLayout = null;

    private TextView statusView = null;

    private View emptyView = null;

    private boolean eventBusRegistered = false;

    private boolean manualRefresh = false;

    final private RecyclerView.AdapterDataObserver checkIfEmptyObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        /**
         * 检查列表是否为空
         */
        void checkIfEmpty() {
            if (emptyView != null && recyclerViewAdapter != null) {
                final boolean emptyViewVisible = recyclerViewAdapter.getItemCount() == 0;
                emptyView.setVisibility(emptyViewVisible ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(emptyViewVisible ? View.GONE : View.VISIBLE);
            }
        }
    };

    public PeerListFragment() {
        this.eventBus = EventBus.getDefault();
    }

    /**
     * Peer 类型转为文本
     */
    public static int peerRoleToString(PeerRole peerRole) {
        switch (peerRole) {
            case PEER_ROLE_PLANET:
                return R.string.peer_role_planet;
            case PEER_ROLE_LEAF:
                return R.string.peer_role_leaf;
            case PEER_ROLE_MOON:
                return R.string.peer_role_moon;
        }
        return 0;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!this.eventBusRegistered) {
            this.eventBus.register(this);
            this.eventBusRegistered = true;
        }
        // 进入页面立即查询一次，随后周期性刷新
        requestPeers(false);
        this.uiHandler.removeCallbacks(this.autoRefreshTask);
        this.uiHandler.postDelayed(this.autoRefreshTask, AUTO_REFRESH_INTERVAL_MS);
    }

    @Override
    public void onStop() {
        this.uiHandler.removeCallbacks(this.autoRefreshTask);
        this.uiHandler.removeCallbacks(this.refreshTimeoutTask);
        if (this.eventBusRegistered) {
            this.eventBus.unregister(this);
            this.eventBusRegistered = false;
        }
        setRefreshing(false);
        super.onStop();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_peer_list, container, false);

        // 空列表提示
        this.emptyView = view.findViewById(R.id.no_data);

        // 列表顶部状态（节点数量 + 最近刷新时间）
        this.statusView = view.findViewById(R.id.peer_update_status);

        // 设置适配器
        this.recyclerView = view.findViewById(R.id.list_peer);
        Context context = this.recyclerView.getContext();
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        this.recyclerViewAdapter = new RecyclerViewAdapter(this.peerList);
        this.recyclerViewAdapter.registerAdapterDataObserver(checkIfEmptyObserver);
        this.recyclerView.setAdapter(this.recyclerViewAdapter);

        // 设置下拉刷新
        this.swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_peer);
        this.swipeRefreshLayout.setOnRefreshListener(this::onRefresh);

        return view;
    }

    /**
     * 下拉刷新：等待真正的回复后再收起 spinner，超时才兜底
     */
    public void onRefresh() {
        requestPeers(true);
    }

    private void requestPeers(boolean manual) {
        if (!isAdded()) {
            return;
        }
        this.manualRefresh = manual;
        this.uiHandler.removeCallbacks(this.refreshTimeoutTask);
        if (manual) {
            setRefreshing(true);
            this.uiHandler.postDelayed(this.refreshTimeoutTask, REFRESH_TIMEOUT_MS);
        }
        this.eventBus.post(new PeerInfoRequestEvent());
    }

    private void setRefreshing(boolean refreshing) {
        if (this.swipeRefreshLayout != null) {
            this.swipeRefreshLayout.setRefreshing(refreshing);
        }
    }

    /**
     * 收到 peer 信息
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPeerInfoReplyEvent(PeerInfoReplyEvent event) {
        if (!isAdded() || getView() == null) {
            return;
        }
        boolean wasManual = this.manualRefresh;
        this.manualRefresh = false;
        this.uiHandler.removeCallbacks(this.refreshTimeoutTask);
        setRefreshing(false);

        Peer[] peers = event.getPeers();
        if (peers == null) {
            // 自动刷新不打扰用户，仅手动刷新时提示
            if (wasManual) {
                Snackbar.make(requireView(), R.string.fail_retrieve_peer_list, BaseTransientBottomBar.LENGTH_LONG).show();
            }
            return;
        }
        // 更新数据列表：优先展示直连的节点，其次按延迟排序
        this.peerList.clear();
        Collections.addAll(this.peerList, peers);
        this.peerList.sort(PeerListFragment::comparePeers);
        this.recyclerViewAdapter.notifyDataSetChanged();
        updateStatus(peers.length);
    }

    /**
     * 更新顶部状态行
     */
    private void updateStatus(int count) {
        if (this.statusView == null) {
            return;
        }
        if (count <= 0) {
            this.statusView.setVisibility(View.GONE);
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        this.statusView.setText(getString(R.string.peer_update_status, count, time));
        this.statusView.setVisibility(View.VISIBLE);
    }

    /**
     * 是否有首选路径（即是否直连）
     */
    private static boolean hasPreferredPath(Peer peer) {
        PeerPhysicalPath[] paths = peer.getPaths();
        if (paths == null) {
            return false;
        }
        for (PeerPhysicalPath path : paths) {
            if (path != null && path.isPreferred()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直连优先，其次延迟小的优先，最后按节点地址排序，保证列表顺序稳定
     */
    private static int comparePeers(Peer left, Peer right) {
        int leftDirect = hasPreferredPath(left) ? 0 : 1;
        int rightDirect = hasPreferredPath(right) ? 0 : 1;
        if (leftDirect != rightDirect) {
            return Integer.compare(leftDirect, rightDirect);
        }
        int leftLatency = left.getLatency() > 0 ? left.getLatency() : Integer.MAX_VALUE;
        int rightLatency = right.getLatency() > 0 ? right.getLatency() : Integer.MAX_VALUE;
        if (leftLatency != rightLatency) {
            return Integer.compare(leftLatency, rightLatency);
        }
        return Long.compare(left.getAddress(), right.getAddress());
    }

    /**
     * Peer 列表适配器
     */
    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {

        private final List<Peer> mValues;

        public RecyclerViewAdapter(List<Peer> items) {
            mValues = items;
        }

        @NonNull
        @Override
        public RecyclerViewAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_peer, parent, false);
            return new RecyclerViewAdapter.ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final RecyclerViewAdapter.ViewHolder holder, int position) {
            Peer peer = mValues.get(position);
            holder.mItem = peer;
            holder.mAddress.setText(String.format(Locale.ROOT, "%010x", peer.getAddress()));
            holder.mRole.setText(peerRoleToString(peer.getRole()));
            // 客户端版本
            String clientVersion = getString(R.string.unknown_version);
            if (peer.getVersionMajor() > 0) {
                clientVersion = StringUtils.peerVersionString(peer);
            }
            holder.mVersion.setText(clientVersion);
            // 延迟
            holder.mLatency.setText(String.format(getString(R.string.peer_lat), peer.getLatency()));
            // 当前路径
            PeerPhysicalPath preferred = null;
            if (peer.getPaths() != null) {
                for (PeerPhysicalPath path : peer.getPaths()) {
                    if (path.isPreferred()) {
                        preferred = path;
                        break;
                    }
                }
            }
            String strPreferred = getString(R.string.peer_relay);
            if (preferred != null) {
                strPreferred = StringUtils.toString(preferred.getAddress());
            }
            holder.mPath.setText(strPreferred);
            // 路径数量
            int pathCount = peer.getPaths() == null ? 0 : peer.getPaths().length;
            holder.mPathCount.setText(getString(R.string.peer_path_count, pathCount));
            // 点击查看详情
            holder.mView.setOnClickListener(v ->
                    PeerDetailDialogFragment.newInstance(peer)
                            .show(getChildFragmentManager(), PeerDetailDialogFragment.TAG));
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        @ToString
        public class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mAddress;
            public final TextView mRole;
            public final TextView mVersion;
            public final TextView mLatency;
            public final TextView mPath;
            public final TextView mPathCount;
            public Peer mItem;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mAddress = view.findViewById(R.id.list_peer_addr);
                mRole = view.findViewById(R.id.list_peer_role);
                mVersion = view.findViewById(R.id.list_peer_ver);
                mLatency = view.findViewById(R.id.list_peer_lat);
                mPath = view.findViewById(R.id.list_peer_path);
                mPathCount = view.findViewById(R.id.list_peer_paths);
            }
        }
    }
}
