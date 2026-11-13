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
    private static final int DATABASE_VERSION = 6;
    
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
        // 唯一索引：同一条消息只允许存一行（时间戳+发送者+来源IP+内容）
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_unique ON " + TABLE_MESSAGES
                + "(" + COL_TIMESTAMP + ", " + COL_SENDER + ", " + COL_SENDER_IP + ", " + COL_CONTENT + ")");
        
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
        if (oldVersion < 6) {
            // v5 → v6: 给消息表加唯一索引，根治「同一条消息被保存两次」。
            // 先清掉历史重复行（按 timestamp+sender+sender_ip+content 分组，保留最小 _id），
            // 否则唯一索引建不起来。
            db.execSQL("DELETE FROM " + TABLE_MESSAGES + " WHERE " + COL_ID + " NOT IN ("
                    + "SELECT MIN(" + COL_ID + ") FROM " + TABLE_MESSAGES + " GROUP BY "
                    + COL_TIMESTAMP + ", " + COL_SENDER + ", " + COL_SENDER_IP + ", " + COL_CONTENT + ")");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_unique ON " + TABLE_MESSAGES
                    + "(" + COL_TIMESTAMP + ", " + COL_SENDER + ", " + COL_SENDER_IP + ", " + COL_CONTENT + ")");
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
        // 自己发的消息原本 uid 是 0，导致好友换 IP 后这些历史消息无法归属（只能凭 IP 猜）。
        // 这里按 peer_ip 从好友表补齐对方 uid，后续迁移历史消息就能按 uid 认领。
        long msgUid = message.getUid();
        if (msgUid == 0 && peerIp != null && !peerIp.isEmpty()) {
            Cursor uidCursor = null;
            try {
                uidCursor = db.rawQuery("SELECT " + COL_FRIEND_UID + " FROM " + TABLE_FRIENDS
                        + " WHERE " + COL_FRIEND_IP + " = ? AND " + COL_FRIEND_UID + " <> 0",
                        new String[]{peerIp});
                if (uidCursor.moveToFirst()) {
                    msgUid = uidCursor.getLong(0);
                }
            } catch (Exception ignored) {
            } finally {
                if (uidCursor != null) uidCursor.close();
            }
        }
        values.put(COL_UID, msgUid);
        
        // 根据peerIp判断聊天类型：null表示房间消息，否则为好友消息
        if (peerIp == null || peerIp.isEmpty()) {
            values.put(COL_CHAT_TYPE, "room");
        } else {
            values.put(COL_CHAT_TYPE, "friend");
        }
        
        // CONFLICT_IGNORE：唯一索引命中（同一条消息重复到达）时返回 -1 而不是抛异常。
        // 之前用 db.insert() 会因为时间戳相同但分两次到达而插成两行。
        return db.insertWithOnConflict(TABLE_MESSAGES, null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
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
     * 添加/更新好友。
     *
     * <p>好友的身份是 <b>uid</b>，不是 IP —— 同一台设备 DHCP 换 IP 后如果按 IP 存，
     * 好友列表里会出现「同一个人好几条、一条在线其余离线」，聊天记录也会因为
     * 按 peer_ip 分组而查不出来。这里统一按 uid 收敛：</p>
     *
     * <ul>
     *   <li>uid 为 0（发现阶段还不知道 uid 的占位行）时，若表里已有同名且带真实 uid 的好友，
     *       认为就是这个人换了 IP，复用那个 uid；</li>
     *   <li>把该 uid 其它 IP 下的旧行删掉，并把旧 IP 下的聊天记录迁到新 IP；</li>
     *   <li>顺手清掉同 IP 下的 uid=0 占位行，避免列表里同一个人出现两次。</li>
     * </ul>
     */
    public long addFriend(String peerIp, long uid, String peerName, String roomCode, int tcpPort) {
        SQLiteDatabase db = getWritableDatabase();

        // 1) uid 未知（0）时，尝试按「同名 + 已有真实 uid」认领成同一个人。
        long effectiveUid = uid;
        if (effectiveUid == 0 && peerName != null && !peerName.isEmpty()) {
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT " + COL_FRIEND_UID + " FROM " + TABLE_FRIENDS
                                + " WHERE " + COL_FRIEND_NAME + " = ? AND " + COL_FRIEND_UID + " <> 0",
                        new String[]{peerName});
                long found = 0;
                int count = 0;
                while (cursor.moveToNext()) {
                    count++;
                    found = cursor.getLong(0);
                }
                // 只有唯一命中才敢认领，同名多人才不猜。
                if (count == 1) {
                    effectiveUid = found;
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        // 2) 按 uid 收敛旧行，并把旧 IP 下的聊天记录迁到新 IP。
        if (effectiveUid != 0 && peerIp != null && !peerIp.isEmpty()) {
            try {
                List<String> oldIps = new ArrayList<>();
                // 旧 IP 有两个来源：
                //  1) 好友表里该 uid 的其它历史行；
                //  2) 消息表里该 uid 出现过的 IP（覆盖「自己发的」那些 uid=0 的行）。
                Cursor cursor = db.rawQuery(
                        "SELECT " + COL_FRIEND_IP + " FROM " + TABLE_FRIENDS
                                + " WHERE " + COL_FRIEND_UID + " = ? AND " + COL_FRIEND_IP + " <> ?"
                                + " UNION "
                                + "SELECT " + COL_PEER_IP + " FROM " + TABLE_MESSAGES
                                + " WHERE " + COL_UID + " = ? AND " + COL_PEER_IP + " IS NOT NULL"
                                + " AND " + COL_PEER_IP + " <> '' AND " + COL_PEER_IP + " <> ?"
                                + " AND COALESCE(" + COL_CHAT_TYPE + ", 'friend') <> 'room'",
                        new String[]{String.valueOf(effectiveUid), peerIp,
                                String.valueOf(effectiveUid), peerIp});
                try {
                    while (cursor.moveToNext()) {
                        String oldIp = cursor.getString(0);
                        if (oldIp != null && !oldIp.isEmpty()) {
                            oldIps.add(oldIp);
                        }
                    }
                } finally {
                    cursor.close();
                }
                if (!oldIps.isEmpty()) {
                    // 单独 try：迁移万一撞上唯一索引失败，也不能影响后面的好友去重。
                    try {
                        ContentValues migrate = new ContentValues();
                        migrate.put(COL_PEER_IP, peerIp);
                        if (peerName != null && !peerName.isEmpty()) {
                            migrate.put(COL_PEER_NAME, peerName);
                        }
                        // 条件：来自该 uid 的消息，或存在旧 IP 下（含自己发的、uid 为 0 的那些）。
                        StringBuilder where = new StringBuilder("(").append(COL_UID).append(" = ?");
                        String[] args = new String[oldIps.size() + 2];
                        args[0] = String.valueOf(effectiveUid);
                        for (int i = 0; i < oldIps.size(); i++) {
                            where.append(" OR ").append(COL_PEER_IP).append(" = ?");
                            args[i + 1] = oldIps.get(i);
                        }
                        where.append(") AND ").append(COL_PEER_IP).append(" <> ?");
                        args[oldIps.size() + 1] = peerIp;
                        // 群聊消息（chat_type=room）不能挪到好友私聊里，必须排除。
                        where.append(" AND COALESCE(").append(COL_CHAT_TYPE).append(", 'friend') <> 'room'");
                        db.update(TABLE_MESSAGES, migrate, where.toString(), args);
                    } catch (Exception e) {
                        AppErrorLogger.catchError(TAG, "迁移好友历史消息失败", e);
                    }
                }
                db.delete(TABLE_FRIENDS,
                        COL_FRIEND_UID + " = ? AND " + COL_FRIEND_IP + " <> ?",
                        new String[]{String.valueOf(effectiveUid), peerIp});
            } catch (Exception ignored) {
            }
        }

        ContentValues values = new ContentValues();
        values.put(COL_FRIEND_IP, peerIp);
        values.put(COL_FRIEND_UID, effectiveUid);
        values.put(COL_FRIEND_NAME, peerName);
        values.put(COL_FRIEND_ROOM_CODE, roomCode);
        values.put(COL_FRIEND_TCP_PORT, tcpPort);
        values.put(COL_FRIEND_ADDED_TIME, System.currentTimeMillis());
        values.put(COL_FRIEND_IS_ONLINE, 0);

        long rowId = db.insertWithOnConflict(TABLE_FRIENDS, null, values, SQLiteDatabase.CONFLICT_REPLACE);

        // 3) 认领成功说明这个 IP 上的 uid=0 占位行是多余的，删掉。
        if (effectiveUid != 0 && peerIp != null && !peerIp.isEmpty()) {
            try {
                db.delete(TABLE_FRIENDS,
                        COL_FRIEND_IP + " = ? AND " + COL_FRIEND_UID + " = 0",
                        new String[]{peerIp});
            } catch (Exception ignored) {
            }
        }
        return rowId;
    }
    
    /**
     * 清理好友表里「同一 uid 多条记录」的历史脏数据。
     *
     * <p>旧版本按 (peer_ip, uid) 存，设备换 IP 就会多出一行；好友列表里
     * 同一个人出现好几次、其中一条在线其余离线。这里对每个 uid 只保留
     * 最近添加的一条。</p>
     */
    public void dedupeFriendsByUid() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("DELETE FROM " + TABLE_FRIENDS + " WHERE " + COL_FRIEND_UID + " <> 0 AND "
                    + COL_FRIEND_UID + " || '|' || " + COL_FRIEND_IP + " NOT IN ("
                    + "SELECT " + COL_FRIEND_UID + " || '|' || " + COL_FRIEND_IP
                    + " FROM " + TABLE_FRIENDS + " WHERE " + COL_FRIEND_UID + " <> 0"
                    + " GROUP BY " + COL_FRIEND_UID
                    + " HAVING MAX(" + COL_FRIEND_ADDED_TIME + "))");
            // 发现阶段留下的 uid=0 占位行：同名的真实好友已经存在就删掉，
            // 否则好友列表里同一个人会出现两条（一条在线、一条离线）。
            db.execSQL("DELETE FROM " + TABLE_FRIENDS + " WHERE " + COL_FRIEND_UID + " = 0 AND "
                    + COL_FRIEND_NAME + " IN (SELECT " + COL_FRIEND_NAME + " FROM " + TABLE_FRIENDS
                    + " WHERE " + COL_FRIEND_UID + " <> 0)");
        } catch (Exception ignored) {
        }
    }

    /**
     * 删除某个 IP 下 uid 为 0 的「手动添加」占位行。
     *
     * <p>手动添加时只知道 IP，先按 uid=0 存一条；握手后拿到真实 uid 会再存一条，
     * 不删占位行的话同一个 IP 会在好友列表里出现两次。</p>
     */
    public void deleteManualPlaceholder(String peerIp) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_FRIENDS,
                    COL_FRIEND_IP + " = ? AND " + COL_FRIEND_UID + " = 0",
                    new String[]{peerIp});
        } catch (Exception ignored) {
        }
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
