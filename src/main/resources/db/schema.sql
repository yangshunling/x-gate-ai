CREATE TABLE IF NOT EXISTS upstream_providers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NOT NULL,
    model_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    fail_count INTEGER NOT NULL DEFAULT 0,
    remark TEXT
);

CREATE TABLE IF NOT EXISTS model_channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    public_model_name TEXT NOT NULL UNIQUE,
    api_key TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 1,
    strategy TEXT NOT NULL DEFAULT 'ROUND_ROBIN',
    model_name TEXT,
    remark TEXT
);

CREATE TABLE IF NOT EXISTS call_logs (
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
