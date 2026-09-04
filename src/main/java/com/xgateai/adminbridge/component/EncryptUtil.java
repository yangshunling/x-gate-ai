package com.xgateai.adminbridge.component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * <p>
 * EncryptUtil 上游 API Key 加解密工具
 * 密钥取 gateway.encryptKey 的 MD5（32 位 hex）作为 AES-256 密钥字节
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class EncryptUtil {

    /**
     * 网关配置
     */
    @Resource
    GatewayConfig gatewayConfig;

    /**
     * AES 加解密实例（默认 ECB/PKCS5Padding）
     */
    private AES aes;

    /**
     * 初始化：由 encryptKey 的 MD5 生成 32 字节密钥并构建 AES 实例
     */
    @PostConstruct
    public void init() {
        // SecureUtil.md5 返回 32 位小写 hex 字符串，取 UTF-8 字节即为 32 字节，适配 AES-256
        byte[] keyBytes = SecureUtil.md5(gatewayConfig.getEncryptKey()).getBytes(StandardCharsets.UTF_8);
        this.aes = new AES(keyBytes);
    }

    /**
     * AES 加密明文，空值或空串原样返回
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
     * AES 解密十六进制密文，空值或空串原样返回；解密异常时记录日志并原样返回输入
     *
     * @param cipherHex 十六进制密文
     * @return 明文；解密失败时为原输入
     */
    public String decrypt(String cipherHex) {
        if (StrUtil.isBlank(cipherHex)) {
            return cipherHex;
        }
        try {
            return aes.decryptStr(cipherHex);
        } catch (Exception e) {
            // 兼容启动期已存在的坏数据，避免解密异常导致上层 NPE
            log.error("AES 解密失败，原样返回输入: {}", cipherHex, e);
            return cipherHex;
        }
    }
}
