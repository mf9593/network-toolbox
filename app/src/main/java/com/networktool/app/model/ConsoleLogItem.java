package com.networktool.app.model;

/**
 * 控制台日志项模型
 */
public class ConsoleLogItem {
    public static final int TYPE_LOG = 0;
    public static final int TYPE_WARN = 1;
    public static final int TYPE_ERROR = 2;
    public static final int TYPE_INFO = 3;

    private String message;
    private String source;
    private int lineNumber;
    private int type;
    private long timestamp;

    public ConsoleLogItem(String message, int type) {
        this.message = message;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public ConsoleLogItem(String message, String source, int lineNumber, int type) {
        this.message = message;
        this.source = source;
        this.lineNumber = lineNumber;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getTypeName() {
        switch (type) {
            case TYPE_LOG:
                return "log";
            case TYPE_WARN:
                return "warn";
            case TYPE_ERROR:
                return "error";
            case TYPE_INFO:
                return "info";
            default:
                return "log";
        }
    }
}
