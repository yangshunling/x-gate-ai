package com.xgateai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p>
 * XGateAiApplication 应用启动类
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@SpringBootApplication
@EnableScheduling
public class XGateAiApplication {

    /**
     * 应用主入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(XGateAiApplication.class, args);
    }

}
