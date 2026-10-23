package com.networktool.app.activity.tool;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.networktool.app.R;
import com.networktool.app.adapter.LanPeerAdapter;
import com.networktool.app.util.DebugLogger;
import com.networktool.app.util.LanChatDatabase;
import com.networktool.app.util.LanChatManager;
import com.networktool.app.util.LanChatNotificationManager;
import com.networktool.app.util.NetworkUtils;
import com.networktool.app.util.ThemeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 好友列表 Fragment
 */
public class LanChatPeersFragment extends Fragment implements LanChatManager.OnPeerChangeListener {

    /** 排序模式 */
    public enum SortMode {
        ONLINE_FIRST,   // 在线优先
        BY_NAME,        // 按名称
        BY_IP           // 按IP
    }

    /** 筛选模式 */
    public enum FilterMode {
        ALL,            // 显示全部
        ROOM_ONLY       // 仅房间
    }

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView peerRecyclerView;
    private View emptyView;
    private EditText searchInput;
    private MaterialCardView searchCard;
    private MaterialCardView sortCardView;
    private ChipGroup sortChipGroup;
    private LanPeerAdapter adapter;
    private List<LanChatManager.LanPeer> allPeers = new ArrayList<>();
    private LanChatDatabase database;
    private LanChatNotificationManager notificationManager;
    private LanChatManager chatManager;
    private SortMode currentSortMode = SortMode.ONLINE_FIRST;
    private FilterMode currentFilterMode = FilterMode.ALL;
    private com.google.android.material.chip.Chip chipFilterRoom;
    private android.content.SharedPreferences prefs;

    public static LanChatPeersFragment newInstance() {
        return new LanChatPeersFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lan_chat_peers, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
            chatManager.addPeerChangeListener(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            android.util.Log.d("LanChatPeers", "onViewCreated starting");

            // 先初始化基础对象
            database = LanChatDatabase.getInstance(requireContext());
            notificationManager = LanChatNotificationManager.getInstance(requireContext());
            chatManager = LanChatManager.getInstance();
            prefs = requireContext().getSharedPreferences("lan_chat", android.content.Context.MODE_PRIVATE);

            // 初始化 Views
            initViews(view);

            // 保护性检查
            if (swipeRefreshLayout == null || peerRecyclerView == null ||
                emptyView == null || searchInput == null) {
                android.util.Log.e("LanChatPeers", "Views initialization failed");
                return;
            }

            setupSwipeRefresh();
            setupRecyclerView();
            setupSearch();
            setupSort();

            // 请求通知权限（Android 13+ 必需）
            requestNotificationPermission();

            // 先加载数据库中的好友列表
            loadFriendsFromDatabase();

            // 确保发现服务已启动，然后刷新
            view.postDelayed(() -> {
                if (!isAdded()) return;
                if (!chatManager.isRunning()) {
                    String username = prefs.getString("user_name", "用户");
                    final android.content.Context appContext = requireContext().getApplicationContext();
                    // 端口协商会等待，不能在主线程执行
                    new Thread(() -> {
                        try {
                            chatManager.start(appContext, username, 0);
                            chatManager.refreshDiscovery();
                        } catch (Exception e) {
                            android.util.Log.e("LanChatPeers", "start chat manager failed", e);
                        }
                    }, "lan-chat-start").start();
                } else {
                    chatManager.refreshDiscovery();
                }
            }, 1000);

            android.util.Log.d("LanChatPeers", "onViewCreated completed successfully");
        } catch (Exception e) {
            android.util.Log.e("LanChatPeers", "onViewCreated failed", e);
        }
    }

    /**
     * 从数据库加载好友列表
     */
    private void loadFriendsFromDatabase() {
        if (adapter == null) {
            android.util.Log.e("LanChatPeers", "adapter is null, cannot load friends");
            return;
        }

        try {
            List<LanChatDatabase.FriendInfo> dbFriends = database.getAllFriends();
            List<LanChatManager.LanPeer> peerList = new ArrayList<>();

            for (LanChatDatabase.FriendInfo friend : dbFriends) {
                LanChatManager.LanPeer peer = new LanChatManager.LanPeer();
                peer.setUid(friend.getUid());
                peer.setIp(friend.getIp());
                peer.setUserName(friend.getName());
                peer.setTcpPort(friend.getTcpPort());
                peer.setFriend(true);
                peer.setOnline(false);
                peer.setLastSeen(0);

                peerList.add(peer);
            }

            allPeers = peerList;

            // 添加公开房间到列表
            addPublicRoomsToList();

            sortPeers();
            adapter.setPeers(allPeers);

            if (allPeers.isEmpty()) {
                if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.GONE);
                if (sortCardView != null) sortCardView.setVisibility(View.GONE);
            } else {
                if (emptyView != null) emptyView.setVisibility(View.GONE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.VISIBLE);
                if (sortCardView != null) sortCardView.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            android.util.Log.e("LanChatPeers", "Failed to load friends from database", e);
        }
    }
    
    /**
     * 添加公开房间到列表
     */
    private void addPublicRoomsToList() {
        try {
            if (chatManager != null) {
                List<LanChatManager.LanPeer> publicRooms = chatManager.getPublicRooms();
                if (publicRooms != null) {
                    for (LanChatManager.LanPeer room : publicRooms) {
                        // 检查是否已存在于列表中（通过IP+端口匹配）
                        boolean exists = false;
                        int existingIndex = -1;
                        for (int i = 0; i < allPeers.size(); i++) {
                            LanChatManager.LanPeer peer = allPeers.get(i);
                            if (peer.getIp() != null && peer.getIp().equals(room.getIp()) &&
                                peer.getTcpPort() == room.getTcpPort()) {
                                exists = true;
                                existingIndex = i;
                                break;
                            }
                        }

                        // 如果不存在，添加到列表；如果存在，更新信息
                        if (!exists) {
                            // 创建房间peer的副本，标记为房间
                            LanChatManager.LanPeer roomPeer = new LanChatManager.LanPeer();
                            roomPeer.setIp(room.getIp());
                            // 房间名称：优先使用roomName，不再加[房间]后缀
                            String roomDisplay = room.getRoomName();
                            if (roomDisplay == null || roomDisplay.isEmpty()) {
                                roomDisplay = room.getUserName();
                                // 去掉可能存在的旧格式后缀
                                if (roomDisplay != null) {
                                    roomDisplay = roomDisplay.replaceAll("\\s*\\[.*?\\]\\s*$", "");
                                }
                            }
                            if (roomDisplay == null || roomDisplay.isEmpty()) {
                                roomDisplay = "聊天室";
                            }
                            roomPeer.setUserName(roomDisplay);
                            roomPeer.setRoomName(roomDisplay);
                            roomPeer.setTcpPort(room.getTcpPort());
                            roomPeer.setFriend(false);
                            roomPeer.setRoom(true);
                            roomPeer.setOnline(room.isOnline());
                            roomPeer.setLastSeen(room.getLastSeen());
                            roomPeer.setDiscoverable(room.isDiscoverable());
                            roomPeer.setRoomCode(room.getRoomCode());
                            // 设置房主IP，任何人进入房间都可以看到房主IP
                            roomPeer.setOwnerIp(room.getOwnerIp() != null ? room.getOwnerIp() : room.getIp());

                            allPeers.add(roomPeer);
                        } else {
                            // 已存在，更新为房间显示
                            LanChatManager.LanPeer existingPeer = allPeers.get(existingIndex);
                            // 房间名称：优先使用roomName，不再加[房间]后缀
                            String roomDisplay = room.getRoomName();
                            if (roomDisplay == null || roomDisplay.isEmpty()) {
                                roomDisplay = room.getUserName();
                                // 去掉可能存在的旧格式后缀
                                if (roomDisplay != null) {
                                    roomDisplay = roomDisplay.replaceAll("\\s*\\[.*?\\]\\s*$", "");
                                }
                            }
                            if (roomDisplay == null || roomDisplay.isEmpty()) {
                                roomDisplay = "聊天室";
                            }
                            existingPeer.setUserName(roomDisplay);
                            existingPeer.setRoomName(roomDisplay);
                            existingPeer.setTcpPort(room.getTcpPort());
                            existingPeer.setOnline(room.isOnline());
                            existingPeer.setLastSeen(room.getLastSeen());
                            existingPeer.setDiscoverable(room.isDiscoverable());
                            existingPeer.setRoom(true);
                            existingPeer.setRoomCode(room.getRoomCode());
                            // 更新房主IP
                            existingPeer.setOwnerIp(room.getOwnerIp() != null ? room.getOwnerIp() : room.getIp());
                            allPeers.set(existingIndex, existingPeer);
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("LanChatPeers", "Failed to add public rooms to list", e);
        }
    }

    /**
     * 请求通知权限（Android 13+ 必需）
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void initViews(View view) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        peerRecyclerView = view.findViewById(R.id.peerRecyclerView);
        emptyView = view.findViewById(R.id.emptyView);
        searchInput = view.findViewById(R.id.searchInput);
        searchCard = view.findViewById(R.id.searchCard);
        sortCardView = view.findViewById(R.id.sortCardView);
        sortChipGroup = view.findViewById(R.id.sortChipGroup);
        chipFilterRoom = view.findViewById(R.id.chipFilterRoom);

        ThemeManager.setupResultCard(searchCard, requireContext());
        ThemeManager.setupResultCard(sortCardView, requireContext());
        searchInput.setTextColor(ThemeManager.getOnSurfaceColor(requireContext()));
        searchInput.setHintTextColor(ThemeManager.getOnSurfaceVariantColor(requireContext()));

        // Chip 颜色跟随主题
        int themeColor = ThemeManager.getAccessibleThemeColor(requireContext());
        int chipBg = ThemeManager.getChipSurfaceColor(requireContext());
        android.content.res.ColorStateList bgCsl = android.content.res.ColorStateList.valueOf(chipBg);
        android.content.res.ColorStateList strokeCsl = android.content.res.ColorStateList.valueOf(themeColor);

        chipFilterRoom.setChipBackgroundColor(bgCsl);
        chipFilterRoom.setChipStrokeColor(strokeCsl);
        chipFilterRoom.setChipStrokeWidth(1);
        chipFilterRoom.setTextColor(ThemeManager.getOnSurfaceColor(requireContext()));

        // 排序 Chip 也跟随主题
        com.google.android.material.chip.Chip chipOnline = view.findViewById(R.id.chipSortOnline);
        com.google.android.material.chip.Chip chipName = view.findViewById(R.id.chipSortName);
        com.google.android.material.chip.Chip chipIp = view.findViewById(R.id.chipSortIp);
        for (com.google.android.material.chip.Chip chip : new com.google.android.material.chip.Chip[]{chipOnline, chipName, chipIp}) {
            if (chip != null) {
                chip.setChipBackgroundColor(bgCsl);
                chip.setChipStrokeColor(strokeCsl);
                chip.setChipStrokeWidth(1);
                chip.setTextColor(ThemeManager.getOnSurfaceColor(requireContext()));
            }
        }

        ImageView emptyIcon = view.findViewById(R.id.emptyIcon);
        if (emptyIcon != null) {
            emptyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ThemeManager.getOnSurfaceVariantColor(requireContext())));
        }
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            LanChatManager.getInstance().refreshDiscovery();
            // 增加延迟时间，让广播消息有足够的时间被接收和处理
            swipeRefreshLayout.postDelayed(() -> {
                if (swipeRefreshLayout.isRefreshing()) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }, 3000);
            Toast.makeText(requireContext(), "正在刷新...", Toast.LENGTH_SHORT).show();
        });

        swipeRefreshLayout.setColorSchemeColors(
            ThemeManager.getAccessibleThemeColor(requireContext()));
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
            ThemeManager.getSurfaceColor(requireContext()));
    }

    private void setupSearch() {
        if (searchInput == null) {
            android.util.Log.e("LanChatPeers", "searchInput is null in setupSearch");
            return;
        }

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterPeers(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void filterPeers(String query) {
        if (adapter == null) return;
        // 统一走 applyCurrentFilter，保证搜索和“仅房间”筛选能同时生效
        applyCurrentFilter();
    }

    /**
     * 设置排序选项
     */
    private void setupSort() {
        if (sortCardView == null || sortChipGroup == null) {
            return;
        }

        // 有好友时显示排序栏
        sortCardView.setVisibility(allPeers != null && !allPeers.isEmpty() ? View.VISIBLE : View.GONE);

        sortChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds == null || checkedIds.isEmpty()) return;

            int checkedId = checkedIds.get(0);
            SortMode newMode;
            if (checkedId == R.id.chipSortName) {
                newMode = SortMode.BY_NAME;
            } else if (checkedId == R.id.chipSortIp) {
                newMode = SortMode.BY_IP;
            } else {
                newMode = SortMode.ONLINE_FIRST;
            }

            if (newMode != currentSortMode) {
                currentSortMode = newMode;
                sortPeers();
                applyCurrentFilter();
            }
        });

        // 房间筛选 chip
        chipFilterRoom.setOnClickListener(v -> {
            if (currentFilterMode == FilterMode.ROOM_ONLY) {
                currentFilterMode = FilterMode.ALL;
                chipFilterRoom.setChecked(false);
            } else {
                currentFilterMode = FilterMode.ROOM_ONLY;
                chipFilterRoom.setChecked(true);
            }
            applyCurrentFilter();
        });
    }

    /**
     * 对好友列表进行排序
     */
    private void sortPeers() {
        if (allPeers == null || allPeers.isEmpty()) return;

        Collections.sort(allPeers, (p1, p2) -> {
            // 房间排在好友前面
            if (p1.isRoom() != p2.isRoom()) {
                return p1.isRoom() ? -1 : 1;
            }

            switch (currentSortMode) {
                case BY_NAME:
                    // 按名称字母顺序排序，在线仍优先于离线
                    if (p1.isOnline() != p2.isOnline()) {
                        return p2.isOnline() ? 1 : -1;
                    }
                    return p1.getDisplayName().compareToIgnoreCase(p2.getDisplayName());
                case BY_IP:
                    // 按IP地址排序，在线仍优先于离线
                    if (p1.isOnline() != p2.isOnline()) {
                        return p2.isOnline() ? 1 : -1;
                    }
                    return compareIp(p1.getIp(), p2.getIp());
                case ONLINE_FIRST:
                default:
                    // 在线优先，同状态下按最后活跃时间降序
                    if (p1.isOnline() != p2.isOnline()) {
                        return p2.isOnline() ? 1 : -1;
                    }
                    return Long.compare(p2.getLastSeen(), p1.getLastSeen());
            }
        });
    }

    /**
     * 比较两个IP地址（按数值逐段比较）
     */
    private int compareIp(String ip1, String ip2) {
        if (ip1 == null && ip2 == null) return 0;
        if (ip1 == null) return -1;
        if (ip2 == null) return 1;

        String[] parts1 = ip1.split("\\.");
        String[] parts2 = ip2.split("\\.");

        int len = Math.min(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            try {
                int octet1 = Integer.parseInt(parts1[i]);
                int octet2 = Integer.parseInt(parts2[i]);
                if (octet1 != octet2) {
                    return Integer.compare(octet1, octet2);
                }
            } catch (NumberFormatException e) {
                // 如果解析失败，回退到字符串比较
                return parts1[i].compareTo(parts2[i]);
            }
        }
        return Integer.compare(parts1.length, parts2.length);
    }

    /**
     * 应用当前搜索过滤（保持排序后的顺序）
     */
    private void applyCurrentFilter() {
        if (adapter == null) return;

        String query = searchInput != null ? searchInput.getText().toString() : "";
        List<LanChatManager.LanPeer> result = new ArrayList<>();
        for (LanChatManager.LanPeer peer : allPeers) {
            // 房间筛选
            if (currentFilterMode == FilterMode.ROOM_ONLY && !peer.isRoom()) {
                continue;
            }
            // 搜索筛选（对空字段做保护）
            if (!query.isEmpty()) {
                String displayName = peer.getDisplayName() == null ? "" : peer.getDisplayName();
                String ip = peer.getIp() == null ? "" : peer.getIp();
                if (!displayName.toLowerCase().contains(query.toLowerCase())
                        && !ip.contains(query)
                        && !String.valueOf(peer.getUid()).contains(query)) {
                    continue;
                }
            }
            result.add(peer);
        }
        adapter.setPeers(result);

        boolean isEmpty = adapter.getItemCount() == 0;
        if (emptyView != null) {
            emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
        if (peerRecyclerView != null) {
            peerRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
    }

    private void setupRecyclerView() {
        adapter = new LanPeerAdapter(new LanPeerAdapter.OnPeerClickListener() {
            @Override
            public void onPeerClick(LanChatManager.LanPeer peer) {
                // 区分好友和房间：房间优先
                if (peer.isRoom()) {
                    // 点击的是房间 - 打开房间聊天页面
                    Intent intent = new Intent(requireContext(), LanChatRoomActivity.class);
                    intent.putExtra("room_code", peer.getRoomCode() != null ? peer.getRoomCode() : "");
                    intent.putExtra("is_room", true);
                    startActivity(intent);
                } else {
                    // 点击的是好友
                    Intent intent = new Intent(requireContext(), LanChatRoomActivity.class);
                    intent.putExtra("peer_ip", peer.getIp());
                    intent.putExtra("peer_name", peer.getUserName());
                    intent.putExtra("peer_port", peer.getTcpPort());
                    startActivity(intent);
                }
            }

            @Override
            public void onPeerLongClick(LanChatManager.LanPeer peer) {
                // 长按显示删除对话框（仅对好友有效）
                if (peer.isFriend()) {
                    showDeletePeerDialog(peer);
                }
            }
        });

        peerRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        peerRecyclerView.setAdapter(adapter);
    }

    /**
     * 显示删除好友对话框
     */
    private void showDeletePeerDialog(LanChatManager.LanPeer peer) {
        if (!isAdded()) return;

        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除好友")
            .setMessage("确定要删除好友 \"" + peer.getDisplayName() + "\" 吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                deletePeer(peer);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 删除好友
     */
    private void deletePeer(LanChatManager.LanPeer peer) {
        if (peer == null || peer.getIp() == null) return;

        try {
            // 从数据库中删除
            database.deleteFriend(peer.getIp());

            // 从内存列表中移除
            allPeers.removeIf(p -> p.getIp() != null && p.getIp().equals(peer.getIp()));

            // 更新适配器
            adapter.setPeers(new ArrayList<>(allPeers));

            // 更新空状态和排序栏
            if (allPeers.isEmpty()) {
                if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.GONE);
                if (sortCardView != null) sortCardView.setVisibility(View.GONE);
            } else {
                if (emptyView != null) emptyView.setVisibility(View.GONE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.VISIBLE);
            }

            Toast.makeText(requireContext(), "已删除好友", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("LanChatPeers", "Error deleting peer", e);
            Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPeerListChanged(List<LanChatManager.LanPeer> peers) {
        try {
            if (getActivity() == null || !isAdded()) {
                return;
            }

            // 如果 peer 列表为空，重新从数据库加载（可能已被清空）
            if (peers == null || peers.isEmpty()) {
                loadFriendsFromDatabase();
                return;
            }

            // 步骤 1: 将当前所有 peer 标记为离线
            for (LanChatManager.LanPeer peer : allPeers) {
                // 只标记好友为离线，房间保持原状态
                if (peer.isFriend()) {
                    peer.setOnline(false);
                }
            }

            // 步骤 2: 更新在线 peer（跳过房间peer，房间由addPublicRoomsToList单独管理）
            for (LanChatManager.LanPeer onlinePeer : peers) {
                if (onlinePeer.getIp() == null || onlinePeer.getIp().isEmpty()) {
                    continue;
                }

                // 跳过房间类型的peer，房间由addPublicRoomsToList单独管理
                if (onlinePeer.isRoom()) {
                    continue;
                }

                // 更新数据库中的好友在线状态
                database.updateFriendOnlineStatus(onlinePeer.getIp(), true);

                // 在列表中查找该 peer（优先通过UID查找，不同UID视为不同好友）
                boolean found = false;
                for (int i = 0; i < allPeers.size(); i++) {
                    LanChatManager.LanPeer existingPeer = allPeers.get(i);
                    if (existingPeer.isRoom()) continue;

                    // 主键匹配：UID相同时视为同一好友
                    if (existingPeer.getUid() == onlinePeer.getUid() && existingPeer.getUid() != 0) {
                        existingPeer.setOnline(true);
                        existingPeer.setLastSeen(System.currentTimeMillis());
                        existingPeer.setUserName(onlinePeer.getUserName());
                        existingPeer.setTcpPort(onlinePeer.getTcpPort());
                        existingPeer.setIp(onlinePeer.getIp());
                        if (onlinePeer.isFriend()) {
                            existingPeer.setFriend(true);
                        }
                        allPeers.set(i, existingPeer);
                        found = true;
                        break;
                    }
                }

                // 如果UID未匹配，用IP+用户名+端口查找
                if (!found) {
                    for (int i = 0; i < allPeers.size(); i++) {
                        LanChatManager.LanPeer existingPeer = allPeers.get(i);
                        if (existingPeer.isRoom()) continue;

                        if (java.util.Objects.equals(existingPeer.getIp(), onlinePeer.getIp()) &&
                            java.util.Objects.equals(existingPeer.getUserName(), onlinePeer.getUserName()) &&
                            existingPeer.getTcpPort() == onlinePeer.getTcpPort()) {
                            existingPeer.setOnline(true);
                            existingPeer.setLastSeen(System.currentTimeMillis());
                            existingPeer.setUserName(onlinePeer.getUserName());
                            existingPeer.setTcpPort(onlinePeer.getTcpPort());
                            if (onlinePeer.isFriend()) {
                                existingPeer.setFriend(true);
                            }
                            allPeers.set(i, existingPeer);
                            found = true;
                            break;
                        }
                    }
                }

                // 如果不存在且是好友，添加到列表
                if (!found && onlinePeer.isFriend()) {
                    allPeers.add(onlinePeer);
                }
            }

            // 步骤 3: 添加公开房间到列表（强制刷新）
            addPublicRoomsToList();

            // 步骤 4: 排序
            sortPeers();

            // 应用当前搜索过滤
            applyCurrentFilter();

            // 显示/隐藏排序栏
            if (sortCardView != null) {
                sortCardView.setVisibility(allPeers != null && !allPeers.isEmpty() ? View.VISIBLE : View.GONE);
            }

            // 显示/隐藏空状态
            if (allPeers.isEmpty()) {
                if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.GONE);
            } else {
                if (emptyView != null) emptyView.setVisibility(View.GONE);
                if (peerRecyclerView != null) peerRecyclerView.setVisibility(View.VISIBLE);
            }

            // 刷新适配器
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        } catch (Exception e) {
            android.util.Log.e("LanChatPeers", "Error in onPeerListChanged", e);
        }
    }

    @Override
    public void onMessageReceived(LanChatManager.LanPeer peer, LanChatManager.ChatMessage message) {
        if (chatManager == null || chatManager.getUserName() == null ||
            message == null || message.getSender() == null) {
            return;
        }

        // 跳过协议消息（不保存到数据库、不显示通知）
        String msgType = message.getType();
        if ("delivery_status".equals(msgType) || "handshake".equals(msgType)
            || "handshake_response".equals(msgType) || "friend_response".equals(msgType)
            || "room_create".equals(msgType) || "room_transfer".equals(msgType)) {
            return;
        }

        // 组播消息（peer=null）不保存到好友数据库，避免房间消息显示在好友聊天窗口
        if (peer == null) {
            return;
        }

        // 跳过自己发送的消息（通过 IP+ 端口判断）
        String localIp = NetworkUtils.getLocalIpAddress();
        if (localIp != null && localIp.equals(message.getSenderIp()) &&
            chatManager.getTcpServerPort() == message.getSenderPort()) {
            return;
        }

        // 根据消息中的 IP 和端口查找或创建 peer
        String senderIp = message.getSenderIp();
        int senderPort = message.getSenderPort();

        if (senderIp == null || senderIp.isEmpty()) {
            // 如果消息中没有 IP，使用 peer 的 IP
            senderIp = peer != null ? peer.getIp() : null;
        }
        if (senderPort <= 0) {
            // 如果消息中没有端口，使用 peer 的端口
            senderPort = peer != null ? peer.getTcpPort() : 0;
        }

        if (senderIp == null || senderIp.isEmpty()) {
            return;
        }

        // 查找是否已存在该 peer
        LanChatManager.LanPeer targetPeer = null;
        for (LanChatManager.LanPeer p : allPeers) {
            if (p.getIp() != null && p.getIp().equals(senderIp)) {
                targetPeer = p;
                break;
            }
        }

        // 如果不存在，创建新的 peer
        if (targetPeer == null) {
            targetPeer = new LanChatManager.LanPeer();
            targetPeer.setUid(message.getUid());
            targetPeer.setIp(senderIp);
            targetPeer.setTcpPort(senderPort);
            targetPeer.setUserName(message.getSender());
            targetPeer.setOnline(true);
            targetPeer.setLastSeen(System.currentTimeMillis());
            targetPeer.setFriend(true); // 收到消息自动成为好友
            allPeers.add(targetPeer);

            // 保存到数据库
            database.addFriend(senderIp, message.getUid(), message.getSender(), null, senderPort);

            // 排序并更新 UI
            sortPeers();
            adapter.setPeers(new ArrayList<>(allPeers));
            if (sortCardView != null) sortCardView.setVisibility(View.VISIBLE);
        }

        // 保存消息到数据库（房间邀请不保存）
        if (!"room_invite".equals(message.getType())) {
            database.saveMessage(senderIp, message.getSender(), message);
        }

        // 显示通知
        if (notificationManager != null) {
            if ("room_invite".equals(message.getType()) && message.getRoomCode() != null) {
                notificationManager.showMessageNotification(
                        message.getSender(),
                        senderIp,
                        message.getContent(),
                        true,
                        message.getRoomCode(),
                        message.getRoomName());
            } else {
                notificationManager.showMessageNotification(
                        message.getSender(),
                        senderIp,
                        message.getContent());
            }
        }
    }

    @Override
    public void onFileRequestReceived(LanChatManager.LanPeer peer, String fileName, long fileSize, String filePath) {
        // 在好友列表页面收到文件请求时，显示通知
        if (notificationManager != null && peer != null) {
            notificationManager.showMessageNotification(
                    peer.getUserName(),
                    peer.getIp(),
                    "发送文件: " + fileName);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (chatManager != null) {
            chatManager.removePeerChangeListener(this);
        }

        if (database != null) {
            for (LanChatManager.LanPeer peer : allPeers) {
                if (peer != null && peer.getIp() != null) {
                    database.updateFriendOnlineStatus(peer.getIp(), false);
                }
            }
        }
    }
}
