-- ============================================================
-- x-gate-ai 建表脚本（幂等，CREATE TABLE IF NOT EXISTS）
-- 启动时由 spring.sql.init.mode=always 自动执行。
-- 表命名（按「它是什么」命名，与实体类对齐）：
--   upstream_provider  上游供应商账号（name/base_url/api_key/enabled）
--   upstream_model     上游供应商下挂载的模型（channel_id 挂接到 upstream_provider，fail_count 在模型行）
--   customer           对外接入凭证（API Key + 路由策略）
--   call_log           调用日志
-- 历史库（旧三表 upstream_providers/model_channels/call_logs，以及
-- x_gate_ 前缀四表）由 DatabaseMigrateRunner 在启动时自动 RENAME/迁移
-- 为本脚本定义的表名。
-- 索引：customer 的 api_key/public_model_name、upstream_model 的
-- (channel_id, model_name) 均由 UNIQUE 约束自动建索引；call_log 的
-- 查询索引由 DatabaseMigrateRunner.ensureIndexes() 在表名就绪后幂等创建
-- （避免 schema.sql 执行时表尚未 RENAME 而报错）。
-- ============================================================

CREATE TABLE IF NOT EXISTS upstream_provider (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS upstream_model (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    model_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    fail_count INTEGER NOT NULL DEFAULT 0,
    max_concurrency INTEGER NOT NULL DEFAULT 0,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (channel_id, model_name)
);

CREATE TABLE IF NOT EXISTS customer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    public_model_name TEXT NOT NULL UNIQUE,
    api_key TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1,
    strategy TEXT NOT NULL DEFAULT 'ROUND_ROBIN',
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS call_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    api_key TEXT,
    public_model TEXT,
    customer_name TEXT,
    upstream_url TEXT,
    upstream_model TEXT,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER NOT NULL DEFAULT 0,
    http_status INTEGER NOT NULL DEFAULT 0,
    request_body TEXT,
    tool_calls_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);
