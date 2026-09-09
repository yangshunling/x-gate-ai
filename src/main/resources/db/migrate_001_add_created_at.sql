-- ============================================================
-- x-gate-ai 数据库迁移脚本（增量，幂等）
-- 执行方式：通过 Spring Boot 的 spring.sql.init.mode=always 自动执行，
--           或手动用 sqlite3 x-gate-ai.db < migrate_001_add_created_at.sql 执行
-- ============================================================

-- 1. upstream_providers 添加 created_at 列
ALTER TABLE upstream_providers ADD COLUMN created_at TEXT;

-- 2. model_channels 添加 created_at 列
ALTER TABLE model_channels ADD COLUMN created_at TEXT;

-- 3. 回填已有数据的创建时间
UPDATE upstream_providers SET created_at = datetime('now', 'localtime') WHERE created_at IS NULL;
UPDATE model_channels SET created_at = datetime('now', 'localtime') WHERE created_at IS NULL;
