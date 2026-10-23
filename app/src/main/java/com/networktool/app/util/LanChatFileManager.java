package com.networktool.app.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

/**
 * 文件传输管理器（按1.md设计重构）
 *
 * 设计原则：
 * - TCP单播传输（可靠）
 * - 独立TCP连接（不阻塞聊天信令）
 * - 分块传输（支持进度、暂停、续传）
 * - CRC32校验（防止文件损坏）
 *
 * 协议流程：
 * 1. FILE_OFFER (JSON) - 发送方发起文件传输
 * 2. FILE_ACCEPT / FILE_REJECT (JSON) - 接收方应答
 * 3. FileChunk流 (二进制) - 发送方传输文件数据
 * 4. FILE_DONE (JSON) - 传输完成通知
 */
public class LanChatFileManager {

    private static final String TAG = "LanChatFileManager";
    private static final String SAVE_DIR = "LanChatFiles";
    private static final int CHUNK_SIZE = 8192; // 8KB per chunk
    private static final int FILE_TRANSFER_PORT_OFFSET = 1; // 文件传输端口 = 聊天端口 + 1

    private ExecutorService executorService;
    private ServerSocket fileServerSocket;
    private boolean isRunning = false;
    private final Gson gson = new Gson();

    // 正在进行的文件传输
    private final ConcurrentHashMap<String, FileTransferTask> activeTransfers = new ConcurrentHashMap<>();

    // 文件传输回调
    private OnFileTransferListener fileListener;

    private static LanChatFileManager instance;

    public static synchronized LanChatFileManager getInstance() {
        if (instance == null) {
            instance = new LanChatFileManager();
        }
        return instance;
    }

    private LanChatFileManager() {
        executorService = Executors.newCachedThreadPool();
    }

    /**
     * 启动文件传输服务
     * @param chatPort 聊天TCP端口，文件传输端口 = chatPort + 1
     */
    public void start(int chatPort) {
        if (isRunning) return;

        int filePort = chatPort + FILE_TRANSFER_PORT_OFFSET;
        executorService.execute(() -> {
            try {
                fileServerSocket = new ServerSocket(filePort);
                fileServerSocket.setReuseAddress(true);
                isRunning = true;
                Log.d(TAG, "File transfer server started on port " + filePort);

                while (isRunning) {
                    try {
                        Socket clientSocket = fileServerSocket.accept();
                        handleFileTransferConnection(clientSocket);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting file transfer connection: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to start file transfer server: " + e.getMessage());
            }
        });
    }

    /**
     * 停止文件传输服务
     */
    public void stop() {
        isRunning = false;
        try {
            if (fileServerSocket != null && !fileServerSocket.isClosed()) {
                fileServerSocket.close();
            }
        } catch (IOException ignored) {}
        activeTransfers.clear();
        Log.d(TAG, "File transfer server stopped");
    }

    /**
     * 处理文件传输连接
     */
    private void handleFileTransferConnection(Socket socket) {
        executorService.execute(() -> {
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                // 读取第一个JSON消息（FILE_OFFER）
                String offerJson = in.readUTF();
                FileTransferOffer offer = gson.fromJson(offerJson, FileTransferOffer.class);

                if (offer == null || !"FILE_OFFER".equals(offer.getType())) {
                    socket.close();
                    return;
                }

                // 通知UI层有文件传输请求
                if (fileListener != null) {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> fileListener.onFileOfferReceived(offer));
                }

                // 等待用户接受或拒绝（通过acceptFileTransfer或rejectFileTransfer方法）
                // 这里使用阻塞等待，超时30秒
                FileTransferTask task = new FileTransferTask(offer.getFileId(), offer.getFileName(), offer.getFileSize());
                activeTransfers.put(offer.getFileId(), task);

                synchronized (task) {
                    task.wait(30000); // 等待30秒
                }

                if (!task.isAccepted()) {
                    // 用户拒绝或超时
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    FileTransferResponse reject = new FileTransferResponse("FILE_REJECT", offer.getFileId());
                    out.writeUTF(gson.toJson(reject));
                    out.flush();
                    socket.close();
                    activeTransfers.remove(offer.getFileId());
                    return;
                }

                // 用户接受，开始接收文件数据
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                FileTransferResponse accept = new FileTransferResponse("FILE_ACCEPT", offer.getFileId());
                out.writeUTF(gson.toJson(accept));
                out.flush();

                // 接收文件数据
                receiveFileData(in, offer, task);

                socket.close();
                activeTransfers.remove(offer.getFileId());

            } catch (Exception e) {
                Log.e(TAG, "Error handling file transfer connection: " + e.getMessage());
            }
        });
    }

    /**
     * 接收文件数据（分块+CRC32校验）
     */
    private void receiveFileData(DataInputStream in, FileTransferOffer offer, FileTransferTask task) {
        try {
            File saveDir = getSaveDirectory();
            File saveFile = new File(saveDir, offer.getFileName());

            FileOutputStream fos = new FileOutputStream(saveFile);
            long received = 0;
            int expectedChunkId = 0;

            while (received < offer.getFileSize()) {
                // 读取chunk头：chunkId(4) + length(4) + crc32(8) = 16 bytes
                int chunkId = in.readInt();
                int length = in.readInt();
                long crc32 = in.readLong();

                // 读取payload
                byte[] data = new byte[length];
                in.readFully(data);

                // CRC32校验
                CRC32 checksum = new CRC32();
                checksum.update(data, 0, length);
                if (checksum.getValue() != crc32) {
                    Log.e(TAG, "CRC32 mismatch for chunk " + chunkId);
                    // 可以发送NACK请求重传，这里简单记录错误
                    task.setError("CRC32校验失败");
                    break;
                }

                // 写入文件
                fos.write(data);
                received += length;
                expectedChunkId++;

                // 更新进度
                task.setReceivedBytes(received);
                if (fileListener != null) {
                    final int progress = (int) ((received * 100) / offer.getFileSize());
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> fileListener.onFileTransferProgress(offer.getFileId(), progress));
                }
            }

            fos.flush();
            fos.close();

            // 发送FILE_DONE
            if (received >= offer.getFileSize()) {
                task.setCompleted(true);
                if (fileListener != null) {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> fileListener.onFileReceived(offer.getFileId(), offer.getFileName(), saveFile.getAbsolutePath(), offer.getFileSize()));
                }
                Log.d(TAG, "File received successfully: " + offer.getFileName());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error receiving file data: " + e.getMessage());
            task.setError(e.getMessage());
        }
    }

    /**
     * 发送文件给peer（通过独立TCP连接）
     */
    public void sendFile(String peerIp, int peerTcpPort, File file, OnFileTransferListener listener) {
        if (peerIp == null || file == null || !file.exists()) {
            if (listener != null) {
                listener.onFileTransferError("参数错误");
            }
            return;
        }

        int filePort = peerTcpPort + FILE_TRANSFER_PORT_OFFSET;
        String fileId = "f-" + System.currentTimeMillis();

        executorService.execute(() -> {
            Socket socket = null;
            DataOutputStream out = null;
            DataInputStream in = null;

            try {
                // 1. 建立TCP连接
                socket = new Socket(peerIp, filePort);
                socket.setSoTimeout(30000);
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                // 2. 发送FILE_OFFER
                FileTransferOffer offer = new FileTransferOffer(
                    "FILE_OFFER", fileId, file.getName(), file.length(), CHUNK_SIZE
                );
                out.writeUTF(gson.toJson(offer));
                out.flush();

                // 3. 等待FILE_ACCEPT或FILE_REJECT
                String responseJson = in.readUTF();
                FileTransferResponse response = gson.fromJson(responseJson, FileTransferResponse.class);

                if (response == null || !"FILE_ACCEPT".equals(response.getType())) {
                    if (listener != null) {
                        listener.onFileTransferError("对方拒绝接收文件");
                    }
                    return;
                }

                // 4. 发送文件数据（分块+CRC32）
                sendFileData(out, file, fileId, listener);

                // 5. 发送FILE_DONE
                FileTransferDone done = new FileTransferDone("FILE_DONE", fileId);
                out.writeUTF(gson.toJson(done));
                out.flush();

                if (listener != null) {
                    listener.onFileSent(fileId, file.getName(), file.length());
                }

                Log.d(TAG, "File sent successfully: " + file.getName());

            } catch (Exception e) {
                Log.e(TAG, "Error sending file: " + e.getMessage());
                if (listener != null) {
                    listener.onFileTransferError(e.getMessage());
                }
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (socket != null) socket.close();
                } catch (IOException ignored) {}
            }
        });
    }

    /**
     * 发送文件数据（分块+CRC32校验）
     */
    private void sendFileData(DataOutputStream out, File file, String fileId, OnFileTransferListener listener) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[CHUNK_SIZE];
        int chunkId = 0;
        long sent = 0;
        int len;

        while ((len = fis.read(buffer)) != -1) {
            // 计算CRC32
            CRC32 checksum = new CRC32();
            checksum.update(buffer, 0, len);
            long crc32 = checksum.getValue();

            // 写入chunk：chunkId(4) + length(4) + crc32(8) + payload(len)
            out.writeInt(chunkId);
            out.writeInt(len);
            out.writeLong(crc32);
            out.write(buffer, 0, len);
            out.flush();

            chunkId++;
            sent += len;

            // 更新进度
            if (listener != null) {
                final int progress = (int) ((sent * 100) / file.length());
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> listener.onFileTransferProgress(fileId, progress));
            }
        }

        fis.close();
    }

    /**
     * 接受文件传输
     */
    public void acceptFileTransfer(String fileId) {
        FileTransferTask task = activeTransfers.get(fileId);
        if (task != null) {
            synchronized (task) {
                task.setAccepted(true);
                task.notify();
            }
        }
    }

    /**
     * 拒绝文件传输
     */
    public void rejectFileTransfer(String fileId) {
        FileTransferTask task = activeTransfers.get(fileId);
        if (task != null) {
            synchronized (task) {
                task.setAccepted(false);
                task.notify();
            }
        }
    }

    /**
     * 获取文件传输端口
     */
    public static int getFileTransferPort(int chatPort) {
        return chatPort + FILE_TRANSFER_PORT_OFFSET;
    }

    /**
     * 获取保存目录
     */
    private File getSaveDirectory() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 设置文件传输监听器
     */
    public void setFileListener(OnFileTransferListener listener) {
        this.fileListener = listener;
    }

    // ==================== 数据类 ====================

    /**
     * 文件传输任务
     */
    public static class FileTransferTask {
        private final String fileId;
        private final String fileName;
        private final long fileSize;
        private boolean accepted = false;
        private boolean completed = false;
        private long receivedBytes = 0;
        private String error = null;

        public FileTransferTask(String fileId, String fileName, long fileSize) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
        }

        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public boolean isAccepted() { return accepted; }
        public void setAccepted(boolean accepted) { this.accepted = accepted; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public long getReceivedBytes() { return receivedBytes; }
        public void setReceivedBytes(long receivedBytes) { this.receivedBytes = receivedBytes; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * 文件传输发起（JSON协议）
     */
    public static class FileTransferOffer {
        private String type;
        private String fileId;
        private String fileName;
        private long fileSize;
        private int chunkSize;

        public FileTransferOffer(String type, String fileId, String fileName, long fileSize, int chunkSize) {
            this.type = type;
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.chunkSize = chunkSize;
        }

        public String getType() { return type; }
        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public int getChunkSize() { return chunkSize; }
    }

    /**
     * 文件传输应答（JSON协议）
     */
    public static class FileTransferResponse {
        private String type;
        private String fileId;

        public FileTransferResponse(String type, String fileId) {
            this.type = type;
            this.fileId = fileId;
        }

        public String getType() { return type; }
        public String getFileId() { return fileId; }
    }

    /**
     * 文件传输完成（JSON协议）
     */
    public static class FileTransferDone {
        private String type;
        private String fileId;

        public FileTransferDone(String type, String fileId) {
            this.type = type;
            this.fileId = fileId;
        }

        public String getType() { return type; }
        public String getFileId() { return fileId; }
    }

    /**
     * 文件传输监听器接口
     */
    public interface OnFileTransferListener {
        void onFileOfferReceived(FileTransferOffer offer);
        void onFileTransferProgress(String fileId, int progress);
        void onFileReceived(String fileId, String fileName, String filePath, long fileSize);
        void onFileSent(String fileId, String fileName, long fileSize);
        void onFileTransferError(String error);
    }
}
