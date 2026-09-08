package com.xgateai.adminbridge.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * <p>
 * DatabaseMigration 轻量数据库迁移组件
 * 在应用启动后执行 schema.sql 无法覆盖的存量库结构调整（如新增列），保证老库平滑升级。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class DatabaseMigration implements ApplicationRunner {

    /**
     * 数据源
     */
    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            if (!hasColumn(connection, "model_channels", "model_name")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE model_channels ADD COLUMN model_name TEXT");
                    log.info("数据库迁移：model_channels 表新增 model_name 列");
                }
            }
            if (!hasColumn(connection, "call_logs", "customer_name")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE call_logs ADD COLUMN customer_name TEXT");
                    log.info("数据库迁移：call_logs 表新增 customer_name 列");
                }
            }
        } catch (SQLException e) {
            log.error("数据库迁移失败", e);
        }
    }

    /**
     * 判断表中是否存在指定列
     */
    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table.toUpperCase(Locale.ROOT) + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
