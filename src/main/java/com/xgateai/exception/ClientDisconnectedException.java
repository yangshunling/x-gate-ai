package com.xgateai.exception;

/**
 * ClientDisconnectedException 客户端断开异常
 * <p>
 * 在 SSE 流式写出时，客户端已断开连接导致写操作失败。
 * 抛出此异常以中断上游读取循环，避免对已断开的连接反复写出产生大量错误日志。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/15
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
