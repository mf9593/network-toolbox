package com.networktool.app.util;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES加密工具类
 * 
 * 用于WiFi数据的加密和解密
 * 使用AES/ECB/PKCS5Padding模式
 */
public class AESUtil {
    
    // AES密钥，必须为16位
    private static final String DEFAULT_KEY = "kdud@jrdi&jhe'hs";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /**
     * 加密
     * @param content 要加密的内容
     * @param key 密钥（16位）
     * @return Base64编码的加密字符串
     */
    public static String encrypt(String content, String key) {
        try {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            SecretKeySpec skeySpec = new SecretKeySpec(raw, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
            byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encrypted, Base64.DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 加密（使用默认密钥）
     */
    public static String encrypt(String content) {
        return encrypt(content, DEFAULT_KEY);
    }

    /**
     * 解密
     * @param encryptedStr Base64编码的加密字符串
     * @param key 密钥（16位）
     * @return 解密后的字符串
     */
    public static String decrypt(String encryptedStr, String key) {
        try {
            // Base64 备份文件通常带换行；先去掉空白，避免部分系统解码失败
            encryptedStr = encryptedStr.replaceAll("\\s+", "");
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            SecretKeySpec skeySpec = new SecretKeySpec(raw, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, skeySpec);
            byte[] encrypted = Base64.decode(encryptedStr, Base64.DEFAULT);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 解密（使用默认密钥）
     */
    public static String decrypt(String encryptedStr) {
        return decrypt(encryptedStr, DEFAULT_KEY);
    }
}
