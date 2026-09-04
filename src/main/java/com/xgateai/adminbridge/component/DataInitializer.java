package com.xgateai.adminbridge.component;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.xgateai.application.entity.User;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * <p>
 * DataInitializer 数据初始化器：启动时若用户表为空，自动创建默认管理员账号 admin
 * 密码取配置 gateway.adminPassword，留空则随机生成并醒目打印到控制台
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    /**
     * 用户 Mapper
     */
    @Resource
    UserMapper userMapper;

    /**
     * 网关配置（管理员初始密码）
     */
    @Resource
    GatewayConfig gatewayConfig;

    /**
     * 应用启动后执行：初始化默认管理员账号
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        // 密码优先取配置 gateway.admin-password，留空则随机生成 12 位
        String password = StrUtil.isNotBlank(gatewayConfig.getAdminPassword())
                ? gatewayConfig.getAdminPassword()
                : RandomUtil.randomString(12);
        User admin = new User();
        admin.setUsername("admin");
        admin.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        admin.setCreatedAt(DateUtil.now());
        userMapper.insert(admin);
        log.info("============================================================");
        log.info("          系统初始化管理员账号成功");
        log.info("          用户名: admin");
        log.info("          初始密码: {}", password);
        log.info("          首次登录后请尽快修改密码！");
        log.info("============================================================");
    }
}
