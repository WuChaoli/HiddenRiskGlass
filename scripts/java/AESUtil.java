package com.hzfj.glasses.utils;

import com.alibaba.fastjson.JSON;
import com.hzfj.glasses.model.dto.AuthCheckBody;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;


public class AESUtil {
    /**
     * AES算法加密
     * @Param:text原文
     * @Param:key密钥
     * */
    public static String AESEncrypt(String text, String key) throws Exception {
        // 创建AES加密算法实例(根据传入指定的秘钥进行加密)
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
        // 初始化为加密模式，并将密钥注入到算法中
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        // 将传入的文本加密
        byte[] encrypted = cipher.doFinal(text.getBytes());
        //生成密文
        // 将密文进行Base64编码，方便传输
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * AES算法解密
     * @Param:base64Encrypted密文
     * @Param:key密钥
     * */
    public static String AESDecrypt(String base64Encrypted, String key) throws Exception {
        // 创建AES解密算法实例
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
        // 初始化为解密模式，并将密钥注入到算法中
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        // 将Base64编码的密文解码
        byte[] encrypted = Base64.getDecoder().decode(base64Encrypted);
        // 解密
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted);
    }

    public static AESSecret getAPPKeyAndSecret() {
        AESSecret aesSecret = new AESSecret();
        SecretKey secretKey = generateAESKey();
        String appKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());
        SecretKeySpec extend = getSecretKey(appKey);
        String appSecret = Base64.getEncoder().encodeToString(extend.getEncoded());
        aesSecret.setAppKey(appKey);
        aesSecret.setAppSecret(appSecret);
        return aesSecret;
    }

    private static SecretKey generateAESKey() {
        KeyGenerator keyGen;
        try {
            keyGen = KeyGenerator.getInstance("AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        keyGen.init(128); // AES key size
        return keyGen.generateKey();
    }

    /**
     * 生成加密秘钥
     *
     * @return
     */
    private static SecretKeySpec getSecretKey(String password) {

        //返回生成指定算法密钥生成器的 KeyGenerator 对象
        KeyGenerator kg;
        try {
            kg = KeyGenerator.getInstance("AES");
            SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
            secureRandom.setSeed(password.getBytes());
            //AES 要求密钥长度为 192
            kg.init(192, secureRandom);
            //生成一个密钥
            SecretKey secretKey = kg.generateKey();
            // 转换为AES专用密钥
            return new SecretKeySpec(secretKey.getEncoded(), "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    public static class AESSecret {

        public String appKey;

        public String appSecret;
    }

    private static final String SECRET = "Btm/Cb6N6glbcOEvjV8qGnyQELjWFUkD";

    public static void main(String[] args) throws Exception {
//        AESSecret appKeyAndSecret = getAPPKeyAndSecret();
//        System.out.println(appKeyAndSecret.getAppKey());
//        System.out.println(appKeyAndSecret.getAppSecret());

        AuthCheckBody body = new AuthCheckBody();
        body.setSnCode("111");
        body.setDate(LocalDate.now());
        String json = JSON.toJSONString(body);
        System.out.println(json);
        String encrypt = AESEncrypt(json, SECRET);
        System.out.println(encrypt);
        String decrypt = AESDecrypt(encrypt, SECRET);
        System.out.println(decrypt);
    }

}
