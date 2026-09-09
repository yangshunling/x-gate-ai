-- ============================================================
-- x-gate-ai 建表脚本（幂等，CREATE TABLE IF NOT EXISTS）
-- 启动时由 spring.sql.init.mode=always 自动执行。
-- 表名统一以 x_gate_ 为前缀，语义：
--   x_gate_channel   渠道/上游账号（name/base_url/api_key/enabled）
--   x_gate_model     渠道下挂载的模型（channel_id 挂接，fail_count 在模型行）
--   x_gate_customer  客户/对外 API Key（原 model_channels）
--   x_gate_call_log  调用日志（原 call_logs）
-- 老库（旧三表 upstream_providers/model_channels/call_logs）由
-- DatabaseMigrateRunner 在启动时自动迁移为上述新表。
-- ============================================================

CREATE TABLE IF NOT EXISTS x_gate_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS x_gate_model (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    model_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    fail_count INTEGER NOT NULL DEFAULT 0,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (channel_id, model_name)
);

CREATE TABLE IF NOT EXISTS x_gate_customer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    public_model_name TEXT NOT NULL UNIQUE,
    api_key TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1,
    strategy TEXT NOT NULL DEFAULT 'ROUND_ROBIN',
    model_name TEXT,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE IF NOT EXISTS x_gate_call_log (
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
