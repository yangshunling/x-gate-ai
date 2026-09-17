package com.xgateai.component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.xgateai.config.GatewayConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * EncryptUtil API Key 加密解密工具
 * <p>
 * 使用 AES-256 加密上游服务的 API Key，密钥由 gateway.encryptKey 的 MD5 派生。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class EncryptUtil {

    private final GatewayConfig gatewayConfig;
    private AES aes;

    /**
     * 构造加密工具
     *
     * @param gatewayConfig 网关配置，用于读取加密密钥
     */
    public EncryptUtil(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
    }

    /**
     * 初始化：从配置中读取加密密钥并构建 AES 实例
     */
    @PostConstruct
    public void init() {
        byte[] keyBytes = SecureUtil.md5(gatewayConfig.getEncryptKey())
                .getBytes(StandardCharsets.UTF_8);
        this.aes = new AES(keyBytes);
        log.info("EncryptUtil 初始化完成，使用密钥: {}", maskKey(gatewayConfig.getEncryptKey()));
    }

    /**
     * AES 加密明文
     *
     * @param plainText 明文
     * @return 密文（十六进制字符串）
     */
    public String encrypt(String plainText) {
        if (StrUtil.isBlank(plainText)) {
            return plainText;
        }
        return aes.encryptHex(plainText);
    }

    /**
     * AES 解密十六进制密文
     *
     * @param cipherHex 十六进制密文
     * @return 明文；解密失败时返回原输入
     */
    public String decrypt(String cipherHex) {
        if (StrUtil.isBlank(cipherHex)) {
            return cipherHex;
        }
        try {
            return aes.decryptStr(cipherHex);
        } catch (Exception e) {
            log.error("AES 解密失败，原样返回输入: {}", maskKey(cipherHex), e);
            return cipherHex;
        }
    }

    /**
     * 密钥脱敏：仅保留首 4 位与尾 4 位，中间以 **** 代替
     *
     * @param key 待脱敏的密钥
     * @return 脱敏后的字符串；空白或长度不足 8 时返回 ***
     */
    private String maskKey(String key) {
        if (StrUtil.isBlank(key) || key.length() < 8) return "***";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
