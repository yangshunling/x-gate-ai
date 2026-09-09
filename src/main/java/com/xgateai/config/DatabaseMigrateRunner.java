package com.xgateai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DatabaseMigrateRunner 存量数据库迁移器（一次性，幂等）
 * <p>
 * 旧版库使用三张旧表（upstream_providers / model_channels / call_logs），
 * 新版统一为 x_gate_ 前缀四张表。此组件在应用启动时检测旧表是否存在，
 * 存在则把存量数据搬入新表并删除旧表，仅执行一次。
 * <ul>
 *   <li>upstream_providers  → x_gate_channel（渠道），其逗号分隔的 model_name 拆分为 x_gate_model 多行</li>
 *   <li>model_channels      → x_gate_customer（客户/API KEY）</li>
 *   <li>call_logs           → x_gate_call_log（调用日志）</li>
 * </ul>
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Slf4j
@Component
public class DatabaseMigrateRunner implements ApplicationRunner {

    private static final String T_CHANNEL = "x_gate_channel";
    private static final String T_MODEL = "x_gate_model";
    private static final String T_CUSTOMER = "x_gate_customer";
    private static final String T_CALL_LOG = "x_gate_call_log";
    private static final String T_OLD_PROVIDER = "upstream_providers";
    private static final String T_OLD_CHANNEL = "model_channels";
    private static final String T_OLD_CALL_LOG = "call_logs";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public DatabaseMigrateRunner(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            enableWalMode();
            migrate();
            ensureNewColumns();
        } catch (Exception e) {
            log.error("数据库结构迁移失败，请检查旧表数据", e);
        }
    }

    private void enableWalMode() {
        try {
            jdbcTemplate.execute("PRAGMA journal_mode=WAL");
            jdbcTemplate.execute("PRAGMA synchronous=NORMAL");
            jdbcTemplate.execute("PRAGMA wal_autocheckpoint=1000");
            log.info("SQLite WAL 模式已启用");
        } catch (Exception e) {
            log.warn("启用 SQLite WAL 模式失败", e);
        }
    }

    /**
     * 为存量新表补充后续迭代新增的列（幂等：已存在则跳过）
     */
    private void ensureNewColumns() {
        if (tableExists(T_MODEL) && !hasColumn(T_MODEL, "max_concurrency")) {
            jdbcTemplate.update("ALTER TABLE " + T_MODEL
                    + " ADD COLUMN max_concurrency INTEGER NOT NULL DEFAULT 0");
            log.info("数据库迁移：x_gate_model 新增列 max_concurrency");
        }
    }

    private void migrate() {
        // 新表是否已存在（schema.sql 已建）；不存在则异常环境，跳过
        if (!tableExists(T_CHANNEL)) {
            return;
        }
        if (!tableExists(T_OLD_PROVIDER) && !tableExists(T_OLD_CHANNEL) && !tableExists(T_OLD_CALL_LOG)) {
            return;
        }

        long newChannelRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + T_CHANNEL, Long.class);
        if (newChannelRows > 0) {
            // 数据已迁移过（可能上次 drop 未完成），仅兜底清理旧表
            log.info("检测到新表已有数据，跳过存量迁移，仅清理残留旧表");
            dropOldTables();
            return;
        }

        txTemplate.executeWithoutResult(status -> doMigrate());
        dropOldTables();
        log.info("存量数据迁移完成：旧三表 → x_gate_ 四表");
    }

    private void doMigrate() {
        migrateProvidersAndModels();
        migrateCustomers();
        migrateCallLogs();
        resetSequences();
    }

    /** upstream_providers → x_gate_channel + x_gate_model（逗号模型拆行） */
    private void migrateProvidersAndModels() {
        boolean hasOld = tableExists(T_OLD_PROVIDER);
        if (!hasOld) {
            return;
        }
        jdbcTemplate.update("INSERT INTO " + T_CHANNEL
                + " (id, name, base_url, api_key, enabled, remark, created_at) "
                + "SELECT id, name, base_url, api_key, enabled, remark, "
                + "COALESCE(created_at, datetime('now', 'localtime')) FROM " + T_OLD_PROVIDER);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, model_name, enabled, fail_count, created_at FROM " + T_OLD_PROVIDER);
        for (Map<String, Object> row : rows) {
            long channelId = ((Number) row.get("id")).longValue();
            int enabled = row.get("enabled") == null ? 1 : ((Number) row.get("enabled")).intValue();
            int failCount = row.get("fail_count") == null ? 0 : ((Number) row.get("fail_count")).intValue();
            String createdAt = row.get("created_at") == null
                    ? null : String.valueOf(row.get("created_at"));
            String models = row.get("model_name") == null ? "" : String.valueOf(row.get("model_name"));
            for (String model : splitModels(models)) {
                jdbcTemplate.update("INSERT INTO " + T_MODEL
                        + " (channel_id, model_name, enabled, fail_count, created_at) VALUES (?, ?, ?, ?, ?)",
                        channelId, model, enabled, failCount, createdAt);
            }
        }
    }

    /** model_channels → x_gate_customer */
    private void migrateCustomers() {
        if (!tableExists(T_OLD_CHANNEL)) {
            return;
        }
        jdbcTemplate.update("INSERT INTO " + T_CUSTOMER
                + " (id, public_model_name, api_key, enabled, strategy, model_name, remark, created_at) "
                + "SELECT id, public_model_name, api_key, enabled, strategy, model_name, remark, "
                + "COALESCE(created_at, datetime('now', 'localtime')) FROM " + T_OLD_CHANNEL);
    }

    /** call_logs → x_gate_call_log（按列复制，兼容老库列差异） */
    private void migrateCallLogs() {
        if (!tableExists(T_OLD_CALL_LOG)) {
            return;
        }
        StringBuilder cols = new StringBuilder("id, api_key, public_model, customer_name, upstream_url,"
                + " upstream_model, input_tokens, output_tokens, latency_ms, http_status,"
                + " request_body, created_at");
        if (hasColumn(T_OLD_CALL_LOG, "tool_calls_count")) {
            cols.append(", tool_calls_count");
        }
        jdbcTemplate.update("INSERT INTO " + T_CALL_LOG + " (" + cols + ") "
                + "SELECT " + cols + " FROM " + T_OLD_CALL_LOG);
    }

    private void resetSequences() {
        for (String table : List.of(T_CHANNEL, T_MODEL, T_CUSTOMER, T_CALL_LOG)) {
            try {
                Long maxId = jdbcTemplate.queryForObject(
                        "SELECT MAX(id) FROM " + table, Long.class);
                if (maxId != null && maxId > 0) {
                    jdbcTemplate.update(
                            "UPDATE sqlite_sequence SET seq = ? WHERE name = ?", maxId, table);
                }
            } catch (Exception e) {
                log.warn("重置 sqlite_sequence 失败, table: {}", table);
            }
        }
    }

    private void dropOldTables() {
        for (String table : List.of(T_OLD_PROVIDER, T_OLD_CHANNEL, T_OLD_CALL_LOG)) {
            if (tableExists(table)) {
                jdbcTemplate.update("DROP TABLE " + table);
            }
        }
    }

    private Set<String> splitModels(String models) {
        Set<String> result = new LinkedHashSet<>();
        if (models == null || models.trim().isEmpty()) {
            return result;
        }
        for (String part : models.split(",")) {
            String m = part.trim();
            if (!m.isEmpty()) {
                result.add(m);
            }
        }
        return result;
    }

    private boolean tableExists(String table) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return cnt != null && cnt > 0;
    }

    private boolean hasColumn(String table, String column) {
        List<Map<String, Object>> cols = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
        for (Map<String, Object> col : cols) {
            if (column.equals(String.valueOf(col.get("name")))) {
                return true;
            }
        }
        return false;
    }
}
