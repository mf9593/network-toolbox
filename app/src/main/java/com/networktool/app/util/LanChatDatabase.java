package com.networktool.app.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.LanChatManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 局域网聊天数据库帮助类
 * 
 * 存储聊天记录到SQLite数据库
 */
public class LanChatDatabase extends SQLiteOpenHelper {
    
    private static final String TAG = "LanChatDatabase";
    private static final String DATABASE_NAME = "lan_chat.db";
    private static final int DATABASE_VERSION = 5;
    
    // 消息表
    private static final String TABLE_MESSAGES = "messages";
    private static final String COL_ID = "_id";
    private static final String COL_PEER_IP = "peer_ip";
    private static final String COL_PEER_NAME = "peer_name";
    private static final String COL_SENDER = "sender";
    private static final String COL_SENDER_IP = "sender_ip";
    private static final String COL_SENDER_PORT = "sender_port";
    private static final String COL_TYPE = "type";  // text, image, file, emoji
    private static final String COL_CONTENT = "content";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_FILE_NAME = "file_name";
    private static final String COL_FILE_SIZE = "file_size";
    private static final String COL_FILE_PATH = "file_path";  // 本地保存路径
    private static final String COL_IS_READ = "is_read";
    private static final String COL_UID = "uid";  // 用户唯一标识
    private static final String COL_CHAT_TYPE = "chat_type";  // friend 或 room
    
    // 好友表
    private static final String TABLE_FRIENDS = "friends";
    private static final String COL_FRIEND_IP = "peer_ip";
    private static final String COL_FRIEND_UID = "uid";  // 用户唯一标识
    private static final String COL_FRIEND_NAME = "peer_name";
    private static final String COL_FRIEND_ROOM_CODE = "room_code";
    private static final String COL_FRIEND_TCP_PORT = "tcp_port";
    private static final String COL_FRIEND_ADDED_TIME = "added_time";
    private static final String COL_FRIEND_IS_ONLINE = "is_online";
    
    private static LanChatDatabase instance;
    
    public static synchronized LanChatDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new LanChatDatabase(context.getApplicationContext());
        }
        return instance;
    }
    
    private LanChatDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_MESSAGES + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_PEER_IP + " TEXT, " +
                COL_PEER_NAME + " TEXT, " +
                COL_SENDER + " TEXT NOT NULL, " +
                COL_SENDER_IP + " TEXT, " +
                COL_SENDER_PORT + " INTEGER DEFAULT 0, " +
                COL_TYPE + " TEXT NOT NULL, " +
                COL_CONTENT + " TEXT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_FILE_NAME + " TEXT, " +
                COL_FILE_SIZE + " INTEGER DEFAULT 0, " +
                COL_FILE_PATH + " TEXT, " +
                COL_IS_READ + " INTEGER DEFAULT 0, " +
                COL_UID + " INTEGER DEFAULT 0, " +
                COL_CHAT_TYPE + " TEXT DEFAULT 'friend'" +
                ")";
        db.execSQL(createTable);
        
        // 创建索引加速查询
        db.execSQL("CREATE INDEX idx_peer_ip ON " + TABLE_MESSAGES + "(" + COL_PEER_IP + ")");
        db.execSQL("CREATE INDEX idx_timestamp ON " + TABLE_MESSAGES + "(" + COL_TIMESTAMP + ")");
        
        // 创建好友表（复合主键：peer_ip + uid）
        String createFriendsTable = "CREATE TABLE " + TABLE_FRIENDS + " (" +
                COL_FRIEND_IP + " TEXT NOT NULL, " +
                COL_FRIEND_UID + " INTEGER DEFAULT 0, " +
                COL_FRIEND_NAME + " TEXT NOT NULL, " +
                COL_FRIEND_ROOM_CODE + " TEXT, " +
                COL_FRIEND_TCP_PORT + " INTEGER DEFAULT 0, " +
                COL_FRIEND_ADDED_TIME + " INTEGER NOT NULL, " +
                COL_FRIEND_IS_ONLINE + " INTEGER DEFAULT 0, " +
                "PRIMARY KEY (" + COL_FRIEND_IP + ", " + COL_FRIEND_UID + ")" +
                ")";
        db.execSQL(createFriendsTable);
        
        // 创建好友索引
        db.execSQL("CREATE INDEX idx_friend_ip ON " + TABLE_FRIENDS + "(" + COL_FRIEND_IP + ")");
        db.execSQL("CREATE INDEX idx_friend_uid ON " + TABLE_FRIENDS + "(" + COL_FRIEND_UID + ")");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1 → v2: 添加 file_path 列
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_FILE_PATH + " TEXT");
        }
        if (oldVersion < 3) {
            // v2 → v3: 添加 sender_ip 和 sender_port 列
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_SENDER_IP + " TEXT");
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_SENDER_PORT + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            // v3 → v4: 添加 uid 列到消息表
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_UID + " INTEGER DEFAULT 0");
            
            // 重建好友表（添加 uid 列，改为复合主键）
            db.execSQL("CREATE TABLE " + TABLE_FRIENDS + "_new (" +
                    COL_FRIEND_IP + " TEXT NOT NULL, " +
                    COL_FRIEND_UID + " INTEGER DEFAULT 0, " +
                    COL_FRIEND_NAME + " TEXT NOT NULL, " +
                    COL_FRIEND_ROOM_CODE + " TEXT, " +
                    COL_FRIEND_TCP_PORT + " INTEGER DEFAULT 0, " +
                    COL_FRIEND_ADDED_TIME + " INTEGER NOT NULL, " +
                    COL_FRIEND_IS_ONLINE + " INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (" + COL_FRIEND_IP + ", " + COL_FRIEND_UID + "))");
            
            // 迁移旧数据
            db.execSQL("INSERT INTO " + TABLE_FRIENDS + "_new (" +
                    COL_FRIEND_IP + ", " + COL_FRIEND_UID + ", " + COL_FRIEND_NAME + ", " +
                    COL_FRIEND_ROOM_CODE + ", " + COL_FRIEND_TCP_PORT + ", " +
                    COL_FRIEND_ADDED_TIME + ", " + COL_FRIEND_IS_ONLINE + ") " +
                    "SELECT " + COL_FRIEND_IP + ", 0, " + COL_FRIEND_NAME + ", " +
                    COL_FRIEND_ROOM_CODE + ", " + COL_FRIEND_TCP_PORT + ", " +
                    COL_FRIEND_ADDED_TIME + ", " + COL_FRIEND_IS_ONLINE + " FROM " + TABLE_FRIENDS);
            
            // 删除旧表，重命名新表
            db.execSQL("DROP TABLE " + TABLE_FRIENDS);
            db.execSQL("ALTER TABLE " + TABLE_FRIENDS + "_new RENAME TO " + TABLE_FRIENDS);
            
            // 创建索引
            db.execSQL("CREATE INDEX idx_friend_ip ON " + TABLE_FRIENDS + "(" + COL_FRIEND_IP + ")");
            db.execSQL("CREATE INDEX idx_friend_uid ON " + TABLE_FRIENDS + "(" + COL_FRIEND_UID + ")");
        }
        if (oldVersion < 5) {
            // v4 → v5: 添加 chat_type 列
            db.execSQL("ALTER TABLE " + TABLE_MESSAGES + " ADD COLUMN " + COL_CHAT_TYPE + " TEXT DEFAULT 'friend'");
        }
    }
    
    /**
     * 保存消息到数据库
     */
    public long saveMessage(String peerIp, String peerName, LanChatManager.ChatMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        
        // 去重：检查是否已存在相同时间戳+内容+发送者的消息
        if (message.getTimestamp() > 0 && message.getContent() != null && message.getSender() != null) {
            Cursor cursor = null;
            try {
                String checkQuery = "SELECT COUNT(*) FROM " + TABLE_MESSAGES +
                        " WHERE " + COL_TIMESTAMP + " = ?" +
                        " AND " + COL_CONTENT + " = ?" +
                        " AND " + COL_SENDER + " = ?";
                cursor = db.rawQuery(checkQuery, new String[]{
                        String.valueOf(message.getTimestamp()),
                        message.getContent(),
                        message.getSender()
                });
                if (cursor.moveToFirst() && cursor.getInt(0) > 0) {
                    cursor.close();
                    return -1; // 已存在，跳过
                }
            } catch (Exception e) {
                AppErrorLogger.catchError("LanChatDatabase", "检查消息去重时出错", e);
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        
        ContentValues values = new ContentValues();
        values.put(COL_PEER_IP, peerIp);
        values.put(COL_PEER_NAME, peerName);
        values.put(COL_SENDER, message.getSender());
        values.put(COL_SENDER_IP, message.getSenderIp());
        values.put(COL_SENDER_PORT, message.getSenderPort());
        values.put(COL_TYPE, message.getType());
        values.put(COL_CONTENT, message.getContent());
        values.put(COL_TIMESTAMP, message.getTimestamp());
        values.put(COL_FILE_NAME, message.getFileName());
        values.put(COL_FILE_SIZE, message.getFileSize());
        values.put(COL_FILE_PATH, message.getFilePath());
        values.put(COL_IS_READ, 1);  // 默认已读
        values.put(COL_UID, message.getUid());
        
        // 根据peerIp判断聊天类型：null表示房间消息，否则为好友消息
        if (peerIp == null || peerIp.isEmpty()) {
            values.put(COL_CHAT_TYPE, "room");
        } else {
            values.put(COL_CHAT_TYPE, "friend");
        }
        
        return db.insert(TABLE_MESSAGES, null, values);
    }
    
    /**
     * 获取与某个好友的聊天记录
     */
    public List<LanChatManager.ChatMessage> getMessages(String peerIp, int limit) {
        List<LanChatManager.ChatMessage> messages = new ArrayList<>();
        
        // 参数校验
        if (peerIp == null || peerIp.isEmpty()) {
            return messages;
        }
        
        SQLiteDatabase db = getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_MESSAGES + 
                " WHERE " + COL_PEER_IP + " = ?" +
                " ORDER BY " + COL_TIMESTAMP + " ASC" +
                (limit > 0 ? " LIMIT " + limit : "");
        
        Cursor cursor = db.rawQuery(query, new String[]{peerIp});
        
        try {
            if (cursor.moveToFirst()) {
                do {
                    LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
                    msg.setSender(cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER)));
                    // 恢复发送方IP和端口（用于去重）
                    int senderIpIndex = cursor.getColumnIndex(COL_SENDER_IP);
                    if (senderIpIndex >= 0) {
                        msg.setSenderIp(cursor.getString(senderIpIndex));
                    }
                    int senderPortIndex = cursor.getColumnIndex(COL_SENDER_PORT);
                    if (senderPortIndex >= 0) {
                        msg.setSenderPort(cursor.getInt(senderPortIndex));
                    }
                    msg.setType(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)));
                    msg.setContent(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)));
                    msg.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
                    msg.setFileName(cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME)));
                    msg.setFileSize(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE)));
                    // 恢复文件路径（用于图片和文件消息的本地访问）
                    int filePathIndex = cursor.getColumnIndex(COL_FILE_PATH);
                    if (filePathIndex >= 0) {
                        msg.setFilePath(cursor.getString(filePathIndex));
                    }
                    messages.add(msg);
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        
        return messages;
    }

    /**
     * 获取房间聊天记录（通过房间码）
     */
    public List<LanChatManager.ChatMessage> getMessagesByRoomCode(String roomCode, int limit) {
        List<LanChatManager.ChatMessage> messages = new ArrayList<>();

        if (roomCode == null || roomCode.isEmpty()) {
            return messages;
        }

        SQLiteDatabase db = getReadableDatabase();

        // 房间消息的 peer_name 字段存储的是 roomCode
        String query = "SELECT * FROM " + TABLE_MESSAGES +
                " WHERE " + COL_PEER_NAME + " = ?" +
                " ORDER BY " + COL_TIMESTAMP + " ASC" +
                (limit > 0 ? " LIMIT " + limit : "");

        Cursor cursor = db.rawQuery(query, new String[]{roomCode});

        try {
            if (cursor.moveToFirst()) {
                do {
                    LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
                    msg.setSender(cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER)));
                    // 恢复发送方IP和端口（用于去重）
                    int senderIpIndex = cursor.getColumnIndex(COL_SENDER_IP);
                    if (senderIpIndex >= 0) {
                        msg.setSenderIp(cursor.getString(senderIpIndex));
                    }
                    int senderPortIndex = cursor.getColumnIndex(COL_SENDER_PORT);
                    if (senderPortIndex >= 0) {
                        msg.setSenderPort(cursor.getInt(senderPortIndex));
                    }
                    msg.setType(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)));
                    msg.setContent(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)));
                    msg.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
                    msg.setFileName(cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME)));
                    msg.setFileSize(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE)));
                    int filePathIndex = cursor.getColumnIndex(COL_FILE_PATH);
                    if (filePathIndex >= 0) {
                        msg.setFilePath(cursor.getString(filePathIndex));
                    }
                    messages.add(msg);
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }

        return messages;
    }
    
    /**
     * 获取未读消息数量
     */
    public int getUnreadCount(String peerIp) {
        SQLiteDatabase db = getReadableDatabase();
        String query = "SELECT COUNT(*) FROM " + TABLE_MESSAGES + 
                " WHERE " + COL_PEER_IP + " = ? AND " + COL_IS_READ + " = 0";
        
        Cursor cursor = db.rawQuery(query, new String[]{peerIp});
        int count = 0;
        try {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }
        return count;
    }
    
    /**
     * 标记消息为已读
     */
    public void markAsRead(String peerIp) {
        // 参数校验
        if (peerIp == null || peerIp.isEmpty()) {
            return;
        }
        
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_IS_READ, 1);
        db.update(TABLE_MESSAGES, values, 
                COL_PEER_IP + " = ? AND " + COL_IS_READ + " = 0", 
                new String[]{peerIp});
    }
    
    /**
     * 删除与某个好友的所有聊天记录
     */
    public void deleteMessages(String peerIp) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, COL_PEER_IP + " = ?", new String[]{peerIp});
    }

    /**
     * 按UID对查询好友聊天记录（通讯隔离：好友消息不泄露到群聊）
     * @param myUid 我的UID
     * @param friendUid 好友的UID
     * @return 两人之间的所有消息
     */
    public List<LanChatManager.ChatMessage> getMessagesByUidPair(long myUid, long friendUid) {
        List<LanChatManager.ChatMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_MESSAGES + " WHERE " +
                "(" + COL_UID + " = ? OR " + COL_UID + " = ?) AND " +
                COL_TYPE + " NOT IN (?, ?, ?, ?) " +
                "ORDER BY " + COL_TIMESTAMP + " ASC";
        
        Cursor cursor = db.rawQuery(query, new String[]{
                String.valueOf(myUid), String.valueOf(friendUid),
                "room_create", "room_invite", "handshake", "presence"
        });
        
        try {
            if (cursor.moveToFirst()) {
                do {
                    LanChatManager.ChatMessage msg = new LanChatManager.ChatMessage();
                    msg.setSender(cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER)));
                    int senderIpIndex = cursor.getColumnIndex(COL_SENDER_IP);
                    if (senderIpIndex >= 0) {
                        msg.setSenderIp(cursor.getString(senderIpIndex));
                    }
                    int senderPortIndex = cursor.getColumnIndex(COL_SENDER_PORT);
                    if (senderPortIndex >= 0) {
                        msg.setSenderPort(cursor.getInt(senderPortIndex));
                    }
                    msg.setType(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)));
                    msg.setContent(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)));
                    msg.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
                    msg.setFileName(cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME)));
                    msg.setFileSize(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE)));
                    int filePathIndex = cursor.getColumnIndex(COL_FILE_PATH);
                    if (filePathIndex >= 0) {
                        msg.setFilePath(cursor.getString(filePathIndex));
                    }
                    int uidIndex = cursor.getColumnIndex(COL_UID);
                    if (uidIndex >= 0) {
                        msg.setUid(cursor.getLong(uidIndex));
                    }
                    messages.add(msg);
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        return messages;
    }

    /**
     * 按UID对删除好友聊天记录（通讯隔离：好友消息不泄露到群聊）
     */
    public void deleteMessagesByUidPair(long myUid, long friendUid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, "(" + COL_UID + " = ? OR " + COL_UID + " = ?)", 
                new String[]{String.valueOf(myUid), String.valueOf(friendUid)});
    }
    
    /**
     * 更新文件保存路径
     */
    public void updateFilePath(long messageId, String filePath) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_FILE_PATH, filePath);
        db.update(TABLE_MESSAGES, values, COL_ID + " = ?", new String[]{String.valueOf(messageId)});
    }
    
    // ==================== 好友相关操作 ====================
    
    /**
     * 添加好友
     */
    public long addFriend(String peerIp, long uid, String peerName, String roomCode, int tcpPort) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_FRIEND_IP, peerIp);
        values.put(COL_FRIEND_UID, uid);
        values.put(COL_FRIEND_NAME, peerName);
        values.put(COL_FRIEND_ROOM_CODE, roomCode);
        values.put(COL_FRIEND_TCP_PORT, tcpPort);
        values.put(COL_FRIEND_ADDED_TIME, System.currentTimeMillis());
        values.put(COL_FRIEND_IS_ONLINE, 0);
        
        return db.insertWithOnConflict(TABLE_FRIENDS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    
    /**
     * 删除好友
     */
    public void deleteFriend(String peerIp) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FRIENDS, COL_FRIEND_IP + " = ?", new String[]{peerIp});
    }

    /**
     * 清空所有聊天记录
     */
    public void clearAllMessages() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, null, null);
    }

    /**
     * 清空所有好友
     */
    public void clearAllFriends() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FRIENDS, null, null);
    }
    
    /**
     * 获取所有好友列表
     */
    public List<FriendInfo> getAllFriends() {
        List<FriendInfo> friends = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_FRIENDS + 
                " ORDER BY " + COL_FRIEND_ADDED_TIME + " ASC";
        
        Cursor cursor = db.rawQuery(query, null);
        
        try {
            if (cursor.moveToFirst()) {
                do {
                    FriendInfo friend = new FriendInfo();
                    friend.setIp(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_IP)));
                    friend.setName(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_NAME)));
                    friend.setRoomCode(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_ROOM_CODE)));
                    friend.setTcpPort(cursor.getInt(cursor.getColumnIndexOrThrow(COL_FRIEND_TCP_PORT)));
                    int uidIdx = cursor.getColumnIndex(COL_FRIEND_UID);
                    if (uidIdx >= 0) {
                        friend.setUid(cursor.getLong(uidIdx));
                    }
                    friend.setAddedTime(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FRIEND_ADDED_TIME)));
                    friend.setOnline(cursor.getInt(cursor.getColumnIndexOrThrow(COL_FRIEND_IS_ONLINE)) == 1);
                    friends.add(friend);
                } while (cursor.moveToNext());
            }
        } finally {
            cursor.close();
        }
        
        return friends;
    }
    
    /**
     * 查询单个好友
     */
    public FriendInfo getFriend(String peerIp) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_FRIENDS, null, COL_FRIEND_IP + " = ?",
                new String[]{peerIp}, null, null, null);
        
        FriendInfo friend = null;
        try {
            if (cursor.moveToFirst()) {
                friend = new FriendInfo();
                friend.setIp(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_IP)));
                friend.setName(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_NAME)));
                friend.setRoomCode(cursor.getString(cursor.getColumnIndexOrThrow(COL_FRIEND_ROOM_CODE)));
                friend.setTcpPort(cursor.getInt(cursor.getColumnIndexOrThrow(COL_FRIEND_TCP_PORT)));
                int uidIdx = cursor.getColumnIndex(COL_FRIEND_UID);
                if (uidIdx >= 0) {
                    friend.setUid(cursor.getLong(uidIdx));
                }
                friend.setAddedTime(cursor.getLong(cursor.getColumnIndexOrThrow(COL_FRIEND_ADDED_TIME)));
                friend.setOnline(cursor.getInt(cursor.getColumnIndexOrThrow(COL_FRIEND_IS_ONLINE)) == 1);
            }
        } finally {
            cursor.close();
        }
        
        return friend;
    }
    
    /**
     * 更新好友在线状态
     */
    public void updateFriendOnlineStatus(String peerIp, boolean isOnline) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_FRIEND_IS_ONLINE, isOnline ? 1 : 0);
        db.update(TABLE_FRIENDS, values, COL_FRIEND_IP + " = ?", new String[]{peerIp});
    }
    
    /**
     * 更新好友信息
     */
    public void updateFriendInfo(String peerIp, String name, int tcpPort) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_FRIEND_NAME, name);
        values.put(COL_FRIEND_TCP_PORT, tcpPort);
        db.update(TABLE_FRIENDS, values, COL_FRIEND_IP + " = ?", new String[]{peerIp});
    }
    
    /**
     * 检查是否是好友
     */
    public boolean isFriend(String peerIp) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_FRIENDS, null, COL_FRIEND_IP + " = ?",
                new String[]{peerIp}, null, null, null);
        
        boolean isFriend = false;
        try {
            isFriend = cursor.moveToFirst();
        } finally {
            cursor.close();
        }
        
        return isFriend;
    }
    
    /**
     * 好友信息模型
     */
    public static class FriendInfo {
        private String ip;
        private String name;
        private String roomCode;
        private int tcpPort;
        private long uid;
        private long addedTime;
        private boolean online;
        
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getRoomCode() { return roomCode; }
        public void setRoomCode(String roomCode) { this.roomCode = roomCode; }
        
        public int getTcpPort() { return tcpPort; }
        public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }
        
        public long getUid() { return uid; }
        public void setUid(long uid) { this.uid = uid; }
        
        public long getAddedTime() { return addedTime; }
        public void setAddedTime(long addedTime) { this.addedTime = addedTime; }
        
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        
        public String getDisplayName() {
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return ip != null ? ip : "未知设备";
        }
    }
}
