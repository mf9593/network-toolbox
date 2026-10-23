package com.networktool.app.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.networktool.app.model.WifiDataItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WiFi数据管理器
 * 
 * 管理WiFi数据的存储、读取、导入、导出
 * 数据使用AES加密存储
 */
public class WifiDataManager {
    private static final String TAG = "WifiDataManager";
    private static final String WIFI_DATA_FILE = "wifi.json";

    private static WifiDataManager instance;

    private Context context;
    private Gson gson;
    private Map<String, List<String>> wifiData;

    public static synchronized WifiDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new WifiDataManager(context.getApplicationContext());
        }
        return instance;
    }

    private WifiDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();
        this.wifiData = new HashMap<>();
        loadData();
    }

    /**
     * 加载WiFi数据
     */
    private void loadData() {
        try {
            File file = new File(context.getFilesDir(), WIFI_DATA_FILE);
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                fis.close();

                String encrypted = sb.toString();
                String decrypted = AESUtil.decrypt(encrypted);
                if (decrypted != null) {
                    Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
                    wifiData = gson.fromJson(decrypted, type);
                    if (wifiData == null) {
                        wifiData = new HashMap<>();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载WiFi数据失败: " + e.getMessage());
            wifiData = new HashMap<>();
        }
    }

    /**
     * 保存WiFi数据
     */
    public void saveData() {
        try {
            String json = gson.toJson(wifiData);
            String encrypted = AESUtil.encrypt(json);
            if (encrypted != null) {
                File file = new File(context.getFilesDir(), WIFI_DATA_FILE);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(encrypted.getBytes());
                fos.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存WiFi数据失败: " + e.getMessage());
        }
    }

    /**
     * 添加WiFi数据
     * @param ssid WiFi名称
     * @param password WiFi密码
     */
    public void addWifi(String ssid, String password) {
        if (ssid == null || ssid.isEmpty()) return;
        if (password == null) password = "";

        if (!wifiData.containsKey(ssid)) {
            wifiData.put(ssid, new ArrayList<>());
        }

        List<String> passwords = wifiData.get(ssid);
        if (!passwords.contains(password)) {
            passwords.add(password);
        }
        saveData();
    }

    /**
     * 批量导入WiFi数据（排除空密码）
     * @param data WiFi数据Map
     */
    public void importData(Map<String, List<String>> data) {
        for (Map.Entry<String, List<String>> entry : data.entrySet()) {
            String ssid = entry.getKey();
            List<String> passwords = entry.getValue();

            // 过滤空密码
            List<String> validPasswords = new ArrayList<>();
            for (String pwd : passwords) {
                if (isValidPassword(pwd)) {
                    validPasswords.add(pwd);
                }
            }
            // 如果过滤后没有有效密码，跳过该SSID
            if (validPasswords.isEmpty()) {
                continue;
            }

            if (!wifiData.containsKey(ssid)) {
                wifiData.put(ssid, new ArrayList<>());
            }

            List<String> existingPasswords = wifiData.get(ssid);
            for (String pwd : validPasswords) {
                if (!existingPasswords.contains(pwd)) {
                    existingPasswords.add(pwd);
                }
            }
        }
        saveData();
    }

    /**
     * 判断密码是否有效（非空、非NONE）
     */
    private boolean isValidPassword(String password) {
        return password != null && !password.isEmpty()
                && !"null".equals(password) && !"NONE".equals(password);
    }

    /**
     * 清除空密码时也要处理导入时用来表示开放网络的“空密码”占位值。
     */
    private boolean isEmptyPassword(String password) {
        return password == null || password.isEmpty()
                || "null".equals(password) || "NONE".equals(password)
                || "空密码".equals(password);
    }

    /**
     * 批量删除所有空密码
     * @return 删除的记录数
     */
    public int removeEmptyPasswords() {
        int removedCount = 0;
        List<String> ssidsToRemove = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : wifiData.entrySet()) {
            String ssid = entry.getKey();
            List<String> passwords = entry.getValue();

            // 移除空密码
            List<String> toRemove = new ArrayList<>();
            for (String pwd : passwords) {
                if (isEmptyPassword(pwd)) {
                    toRemove.add(pwd);
                }
            }
            passwords.removeAll(toRemove);
            removedCount += toRemove.size();

            // 如果该SSID没有密码了，标记删除
            if (passwords.isEmpty()) {
                ssidsToRemove.add(ssid);
            }
        }

        // 删除没有密码的SSID
        for (String ssid : ssidsToRemove) {
            wifiData.remove(ssid);
        }

        if (removedCount > 0) {
            saveData();
        }
        return removedCount;
    }

    /**
     * 解析导入的WiFi数据字符串
     * @param content 文件内容
     * @return 解析后的WiFi数据
     */
    public Map<String, List<String>> parseWifiData(String content) {
        Map<String, List<String>> result = new HashMap<>();

        if (content == null || content.isEmpty()) {
            return result;
        }

        // 尝试解析加密的JSON格式
        String decrypted = AESUtil.decrypt(content);
        if (decrypted != null) {
            try {
                Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
                Map<String, List<String>> data = gson.fromJson(decrypted, type);
                if (data != null) {
                    return data;
                }
            } catch (Exception e) {
                Log.d(TAG, "不是加密JSON Map格式");
            }

            // 尝试解析加密的APP自身导出格式（JSON数组）
            parseAppExportJsonFormat(decrypted, result);
            if (!result.isEmpty()) {
                return result;
            }

            // 兼容旧版导出的加密 title/content 格式
            parseTitleContentJsonFormat(decrypted, result);
            if (result.isEmpty()) {
                parseTitleContentObjectFormat(decrypted, result);
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 尝试解析 vivo/设置备份中的 wifi_saved_network
        parseVivoSavedNetworks(content, result);
        if (!result.isEmpty()) {
            return result;
        }

        // 尝试解析vivo格式
        if (content.contains("vivo_wifi")) {
            parseVivoFormat(content, result);
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 尝试解析network={}格式
        // 小米和大多数Android设备都使用这种格式
        if (content.contains("network={") || content.contains("network={")) {
            parseNetworkFormat(content, result);
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 尝试解析普通JSON格式（无加密）
        try {
            Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
            Map<String, List<String>> data = gson.fromJson(content, type);
            if (data != null) {
                return data;
            }
        } catch (Exception e) {
            Log.d(TAG, "不是JSON格式");
        }

        // 尝试解析APP自身导出格式（明文JSON数组）
        parseAppExportJsonFormat(content, result);
        if (!result.isEmpty()) {
            return result;
        }

        // 尝试解析带 title/content 的 JSON 格式（数组或对象）
        try {
            parseTitleContentJsonFormat(content, result);
            if (result.isEmpty()) {
                parseTitleContentObjectFormat(content, result);
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            Log.d(TAG, "不是 title/content JSON 格式");
        }

        // 尝试解析APP自身导出的TXT格式
        parseTxtFormat(content, result);
        if (!result.isEmpty()) {
            return result;
        }

        // 兼容被导出成文本的 SQLite wifiConfig 表
        parseLegacyWifiConfig(content, result);

        return result;
    }
    
    /**
     * 解析带title/content的JSON格式
     */
    private void parseTitleContentJsonFormat(String content, Map<String, List<String>> result) {
        try {
            // 尝试解析为数组格式 [{"title":"xxx", "content":"xxx"}, ...]
            Type listType = new TypeToken<List<Map<String, String>>>(){}.getType();
            List<Map<String, String>> dataList = gson.fromJson(content, listType);

            if (dataList != null) {
                for (Map<String, String> item : dataList) {
                    String title = item.get("title");
                    String contentValue = item.get("content");

                    if (title != null && !title.isEmpty() && !title.contains("隐藏 SSID")) {
                        if (!result.containsKey(title)) {
                            result.put(title, new ArrayList<>());
                        }
                        if (contentValue != null && !result.get(title).contains(contentValue)) {
                            result.get(title).add(contentValue);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 解析对象形式的 title/content JSON：
     * {"1":{"title":"SSID","content":"password"}, ...}
     */
    private void parseTitleContentObjectFormat(String content, Map<String, List<String>> result) {
        boolean parsed = false;
        try {
            Type type = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
            Map<String, Map<String, String>> dataMap = gson.fromJson(content, type);
            if (dataMap != null) {
                for (Map<String, String> item : dataMap.values()) {
                    if (item == null) continue;
                    String title = item.get("title");
                    String password = item.get("content");
                    if (title == null || title.isEmpty() || title.contains("隐藏 SSID")) continue;
                    if (password == null || password.isEmpty()) password = "空密码";
                    addWifiRecord(result, title, password);
                }
            }
            parsed = true;
        } catch (Exception ignored) {
            // 部分旧版备份的 JSON 对象键重复，Gson 会严格拒绝整份 JSON。
            // 下面改用 title/content 逐条提取，保留所有记录。
        }

        if (!parsed || result.isEmpty()) {
            parseTitleContentObjectByPattern(content, result);
        }
    }

    /**
     * 容错解析 {"key":{"title":"SSID","content":"密码"}}。
     * 旧版备份可能存在重复对象键，严格 JSON 解析会失败。
     */
    private void parseTitleContentObjectByPattern(String content,
                                                  Map<String, List<String>> result) {
        try {
            Pattern pattern = Pattern.compile(
                    "\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*" +
                            "\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String title = unescapeJsonString(matcher.group(1));
                String password = unescapeJsonString(matcher.group(2));
                if (title == null || title.isEmpty() || title.contains("隐藏 SSID")) {
                    continue;
                }
                if (password == null || password.isEmpty()) {
                    password = "空密码";
                }
                addWifiRecord(result, title, password);
            }
        } catch (Exception ignored) {
        }
    }

    private String unescapeJsonString(String value) {
        if (value == null) return "";
        if (value.indexOf('\\') < 0) return value;
        try {
            return gson.fromJson("\"" + value + "\"", String.class);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 解析APP自身导出的JSON数组格式
     * 格式: [{"ssid":"xxx", "password":"xxx", "timestamp":"xxx"}, ...]
     */
    private void parseAppExportJsonFormat(String content, Map<String, List<String>> result) {
        try {
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String ssid = item.optString("ssid", "");
                String password = item.optString("password", "");
                if (ssid.isEmpty()) continue;
                if (password.isEmpty()) password = "空密码";
                addWifiRecord(result, ssid, password);
            }
        } catch (Exception e) {
            // 忽略，不是此格式
        }
    }

    /**
     * 解析APP自身导出的TXT格式
     * 格式:
     *   SSID: xxx
     *   密码: xxx
     *   ------------------------
     */
    private void parseTxtFormat(String content, Map<String, List<String>> result) {
        try {
            String[] lines = content.split("\\n");
            String currentSsid = null;

            for (String line : lines) {
                line = line.trim();

                if (line.startsWith("SSID:") || line.startsWith("SSID：")) {
                    // 提取SSID
                    String ssid = line.substring(line.indexOf(":") + 1).trim();
                    if (ssid.isEmpty() && line.contains("：")) {
                        ssid = line.substring(line.indexOf("：") + 1).trim();
                    }
                    if (!ssid.isEmpty()) {
                        currentSsid = ssid;
                    }
                } else if (line.startsWith("密码:") || line.startsWith("密码：")) {
                    // 提取密码
                    String password = line.substring(line.indexOf(":") + 1).trim();
                    if (password.isEmpty() && line.contains("：")) {
                        password = line.substring(line.indexOf("：") + 1).trim();
                    }
                    if (currentSsid != null && !currentSsid.isEmpty()
                            && password != null && !password.isEmpty()) {
                        if (!result.containsKey(currentSsid)) {
                            result.put(currentSsid, new ArrayList<>());
                        }
                        if (!result.get(currentSsid).contains(password)) {
                            result.get(currentSsid).add(password);
                        }
                    }
                } else if (line.startsWith("---")) {
                    // 分隔符，重置当前SSID
                    currentSsid = null;
                }
            }
        } catch (Exception e) {
            // 忽略，不是此格式
        }
    }

    /**
     * 解析vivo格式
     */
    private void parseVivoFormat(String content, Map<String, List<String>> result) {
        // 新版 vivo 设置备份把 WiFi 列表保存在 wifi_saved_network 的 JSON 字符串里
        parseVivoSavedNetworks(content, result);
        if (!result.isEmpty()) {
            return;
        }

        // 旧格式：SSID...preSharedKey... 文本
        content = content.replace("&quot;", "");
        content = content.replace("\"", "");
        
        // 匹配 SSID...preSharedKey... 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "SSID([^,]+),([^,]+)preSharedKey([^,]+),");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String ssidPart = matcher.group(1).trim();
            String passwordPart = matcher.group(3).trim();
            
            // 从 \\xxx\\ 格式中提取实际值
            String ssid = extractValueFromBackslash(ssidPart);
            String password = extractValueFromBackslash(passwordPart);
            
            // 过滤无效值
            if (ssid != null && !ssid.isEmpty() && 
                !ssid.equals(":false") && !ssid.equals(":true")) {
                if (!result.containsKey(ssid)) {
                    result.put(ssid, new ArrayList<>());
                }
                if (password != null && !result.get(ssid).contains(password)) {
                    result.get(ssid).add(password);
                }
            }
        }
    }
    
    /**
     * 从反斜杠格式中提取值
     */
    private String extractValueFromBackslash(String input) {
        if (input == null) return null;
        
        // 尝试匹配 \xxx\ 格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\\\(.+?)\\\\");
        java.util.regex.Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 如果没有反斜杠，直接返回
        return input.trim();
    }

    /**
     * 解析 vivo 设置备份中的 wifi_saved_network JSON。
     */
    private void parseVivoSavedNetworks(String content, Map<String, List<String>> result) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "name=\"wifi_saved_network\"[^>]*>(.*?)</string>",
                    java.util.regex.Pattern.DOTALL).matcher(content);
            while (matcher.find()) {
                String raw = matcher.group(1);
                // 部分备份文件在 JSON 中混入了 NUL/控制字符
                raw = raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
                String json = unescapeXmlEntities(raw).trim();
                if (json.isEmpty()) continue;
                try {
                    JSONArray array = new JSONArray(json);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        String ssid = cleanWifiValue(item.optString("SSID", ""));
                        String password = cleanWifiValue(item.optString("preSharedKey", ""));
                        String keyMgmt = item.optString("allowedKeyManagement", "");
                        if (ssid.isEmpty()) continue;
                        if (password.isEmpty()) {
                            if (keyMgmt.contains("0") || item.optBoolean("hiddenSSID", false)) {
                                password = "空密码";
                            } else {
                                continue;
                            }
                        }
                        addWifiRecord(result, ssid, password);
                    }
                } catch (Exception jsonError) {
                    // 个别厂商备份中的 JSON 有控制字符/实体损坏，按 SSID 记录逐条提取
                    parseVivoByPattern(json, result);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String unescapeXmlEntities(String value) {
        if (value == null) return "";
        String result = value;
        for (int i = 0; i < 2; i++) {
            result = result.replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">");
        }
        return result;
    }

    private String cleanWifiValue(String value) {
        if (value == null) return "";
        value = value.trim().replace("\\\"", "\"");
        while (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.trim();
    }

    private void parseVivoByPattern(String json, Map<String, List<String>> result) {
        try {
            Matcher sm = Pattern.compile("\"SSID\":\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(json);
            List<Integer> starts = new ArrayList<>();
            List<String> ssids = new ArrayList<>();
            while (sm.find()) {
                starts.add(sm.start());
                ssids.add(sm.group(1));
            }
            for (int i = 0; i < starts.size(); i++) {
                int end = i + 1 < starts.size() ? starts.get(i + 1) : json.length();
                String chunk = json.substring(starts.get(i), end);
                Matcher pm = Pattern.compile("\"preSharedKey\":\"((?:\\\\.|[^\"\\\\])*)\"")
                        .matcher(chunk);
                String password = pm.find() ? cleanWifiValue(pm.group(1)) : "";
                String ssid = cleanWifiValue(ssids.get(i));
                if (ssid.isEmpty()) continue;
                if (password.isEmpty()
                        && (chunk.contains("\"allowedKeyManagement\":[0]")
                        || chunk.contains("\"keyMgmt\":\"NONE\""))) {
                    password = "空密码";
                }
                if (!password.isEmpty()) {
                    addWifiRecord(result, ssid, password);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void addWifiRecord(Map<String, List<String>> result, String ssid, String password) {
        if (ssid == null || ssid.isEmpty()) return;
        if (!result.containsKey(ssid)) {
            result.put(ssid, new ArrayList<>());
        }
        if (password != null && !password.isEmpty()
                && !result.get(ssid).contains(password)) {
            result.get(ssid).add(password);
        }
    }

    /**
     * 兼容某些导出成文本的 SQLite wifiConfig 表。
     * 典型字符串: false"password""SSID"WPA_PSK / false"SSID"NONE
     */
    private void parseLegacyWifiConfig(String content, Map<String, List<String>> result) {
        try {
            java.util.regex.Matcher wpaMatcher = java.util.regex.Pattern.compile(
                    "(?:true|false)?\"([^\"]{1,128})\"\"([^\"]{1,128})\"(?:WPA_PSK|WPA2_PSK|WPA3_SAE|SAE)")
                    .matcher(content);
            while (wpaMatcher.find()) {
                addWifiRecord(result, wpaMatcher.group(2), wpaMatcher.group(1));
            }
            java.util.regex.Matcher openMatcher = java.util.regex.Pattern.compile(
                    "(?:true|false)?\"([^\"]{1,128})\"NONE").matcher(content);
            while (openMatcher.find()) {
                addWifiRecord(result, openMatcher.group(1), "空密码");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 解析network={}格式
     */
    private void parseNetworkFormat(String content, Map<String, List<String>> result) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "network=\\{(.*?)\\}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String block = matcher.group(1);
            String ssid = null;
            String password = null;

            // 提取ssid
            java.util.regex.Pattern ssidPattern = java.util.regex.Pattern.compile(
                    "[Ss][Ss][Ii][Dd]=\"(.*?)\"");
            java.util.regex.Matcher ssidMatcher = ssidPattern.matcher(block);
            if (ssidMatcher.find()) {
                ssid = ssidMatcher.group(1);
            }

            // 提取psk
            // 先尝试 psk=
            java.util.regex.Pattern pskPattern = java.util.regex.Pattern.compile(
                    "psk=\"(.*?)\"");
            java.util.regex.Matcher pskMatcher = pskPattern.matcher(block);
            if (pskMatcher.find()) {
                password = pskMatcher.group(1).trim();
            } else {
                // 尝试 PreSharedKey=
                pskPattern = java.util.regex.Pattern.compile(
                        "[Pp]re[Ss]hared[Kk][Ee]y=\"(.*?)\"");
                pskMatcher = pskPattern.matcher(block);
                if (pskMatcher.find()) {
                    password = pskMatcher.group(1).trim();
                } else {
                    // 尝试无引号的 psk=
                    pskPattern = java.util.regex.Pattern.compile(
                            "[Pp][Ss][Kk]=(.*?)[\\n\\r]");
                    pskMatcher = pskPattern.matcher(block);
                    if (pskMatcher.find()) {
                        password = pskMatcher.group(1).trim();
                        // 去掉引号
                        if (password.startsWith("\"") && password.endsWith("\"")) {
                            password = password.substring(1, password.length() - 1);
                        }
                    }
                }
            }

            // 过滤null、NONE和空值；无密码的开放网络保留为“空密码”
            if (ssid != null && !ssid.isEmpty()) {
                if (password == null || password.isEmpty()) {
                    String keyMgmt = "";
                    // MIUI 备份使用 AllowedKeyMgmt，AOSP 使用 key_mgmt
                    java.util.regex.Matcher keyMatcher = java.util.regex.Pattern.compile(
                            "(?i)(?:key_mgmt|AllowedKeyMgmt)=([^\\n\\r]+)").matcher(block);
                    if (keyMatcher.find()) {
                        keyMgmt = keyMatcher.group(1).trim().toUpperCase();
                    }
                    if (keyMgmt.contains("NONE") || keyMgmt.contains("OWE")
                            || "01".equals(keyMgmt) || "0002".equals(keyMgmt)) {
                        password = "空密码";
                    }
                }
                if (password != null && !password.isEmpty()
                        && !"null".equals(password) && !"NONE".equals(password)) {
                    if (!result.containsKey(ssid)) {
                        result.put(ssid, new ArrayList<>());
                    }
                    if (!result.get(ssid).contains(password)) {
                        result.get(ssid).add(password);
                    }
                }
            }
        }
    }

    /**
     * 获取所有WiFi数据
     */
    public Map<String, List<String>> getAllWifiData() {
        return wifiData;
    }

    /**
     * 获取WiFi数据列表（同名WiFi只显示一个）
     */
    public List<WifiDataItem> getWifiDataList() {
        List<WifiDataItem> list = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : wifiData.entrySet()) {
            String ssid = entry.getKey();
            List<String> passwords = entry.getValue();
            
            // 只显示第一个密码
            if (passwords != null && !passwords.isEmpty()) {
                String firstPassword = passwords.get(0);
                WifiDataItem item = new WifiDataItem(ssid, firstPassword);
                item.setPasswordCount(passwords.size()); // 记录密码总数
                list.add(item);
            }
        }
        return list;
    }

    /**
     * 获取WiFi数据完整列表（展开所有密码）
     * 用于导出等需要所有密码的场景
     */
    public List<WifiDataItem> getWifiDataListFull() {
        List<WifiDataItem> list = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : wifiData.entrySet()) {
            String ssid = entry.getKey();
            for (String password : entry.getValue()) {
                WifiDataItem item = new WifiDataItem(ssid, password);
                list.add(item);
            }
        }
        return list;
    }

    /**
     * 删除指定WiFi
     */
    public void deleteWifi(String ssid, String password) {
        if (wifiData.containsKey(ssid)) {
            List<String> passwords = wifiData.get(ssid);
            passwords.remove(password);
            if (passwords.isEmpty()) {
                wifiData.remove(ssid);
            }
            saveData();
        }
    }

    /**
     * 清空所有数据
     */
    public void clearAll() {
        wifiData.clear();
        saveData();
    }

    /**
     * 获取WiFi数量
     */
    public int getWifiCount() {
        int count = 0;
        for (List<String> passwords : wifiData.values()) {
            count += passwords.size();
        }
        return count;
    }

    /**
     * 导出数据为JSON字符串（全量）
     */
    public String exportToString() {
        return gson.toJson(wifiData);
    }

    /**
     * 导出选中项为JSON字符串
     */
    public String exportSelectedToString(List<WifiDataItem> selectedList) {
        Map<String, List<String>> selected = new HashMap<>();
        for (WifiDataItem item : selectedList) {
            selected.computeIfAbsent(item.getSsid(), k -> new ArrayList<>()).add(item.getPassword());
        }
        return gson.toJson(selected);
    }
}
