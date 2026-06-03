-- =========================
-- 2. user TABLE
-- =========================
DROP TRIGGER IF EXISTS trg_user_insert;
CREATE TRIGGER trg_user_insert
AFTER INSERT ON user
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_user_update;
CREATE TRIGGER trg_user_update
AFTER UPDATE ON user
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_user_delete;
CREATE TRIGGER trg_user_delete
AFTER DELETE ON user
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user', 'DELETE', OLD.id);
END;

-- =========================
-- 3. sqlite_sequence TABLE
-- =========================

/*CREATE TRIGGER trg_sqlite_sequence_insert
AFTER INSERT ON sqlite_sequence
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('sqlite_sequence', 'INSERT', NEW.id);
END;

CREATE TRIGGER trg_sqlite_sequence_update
AFTER UPDATE ON sqlite_sequence
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('sqlite_sequence', 'UPDATE', NEW.id);
END;

CREATE TRIGGER trg_sqlite_sequence_delete
AFTER DELETE ON sqlite_sequence
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('sqlite_sequence', 'DELETE', OLD.id);
END;*/

-- =========================
-- 4. user_activation_history TABLE
-- =========================
DROP TRIGGER IF EXISTS trg_user_activation_history_insert;
CREATE TRIGGER trg_user_activation_history_insert
AFTER INSERT ON user_activation_history
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_activation_history', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_user_activation_history_update;
CREATE TRIGGER trg_user_activation_history_update
AFTER UPDATE ON user_activation_history
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_activation_history', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_user_activation_history_delete;
CREATE TRIGGER trg_user_activation_history_delete
AFTER DELETE ON user_activation_history
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_activation_history', 'DELETE', OLD.id);
END;

-- =========================
-- 5. recent_usernames TABLE
-- =========================
DROP TRIGGER IF EXISTS trg_recent_usernames_insert;
CREATE TRIGGER trg_recent_usernames_insert
AFTER INSERT ON recent_usernames
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('recent_usernames', 'INSERT', NEW.username);
END;

DROP TRIGGER IF EXISTS trg_recent_usernames_update;
CREATE TRIGGER trg_recent_usernames_update
AFTER UPDATE ON recent_usernames
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('recent_usernames', 'UPDATE', NEW.username);
END;

DROP TRIGGER IF EXISTS trg_recent_usernames_delete;
CREATE TRIGGER trg_recent_usernames_delete
AFTER DELETE ON recent_usernames
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('recent_usernames', 'DELETE', OLD.username);
END;

-- =========================
-- 6. files TABLE
-- =========================
DROP TRIGGER IF EXISTS trg_files_insert;
CREATE TRIGGER trg_files_insert
AFTER INSERT ON files
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('files', 'INSERT', NEW.file_id);
END;

DROP TRIGGER IF EXISTS trg_files_update;
CREATE TRIGGER trg_files_update
AFTER UPDATE ON files
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('files', 'UPDATE', NEW.file_id);
END;

DROP TRIGGER IF EXISTS trg_files_delete;
CREATE TRIGGER trg_files_delete
AFTER DELETE ON files
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('files', 'DELETE', OLD.file_id);
END;

-- =========================
-- 7. app_config TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_app_config_insert;
CREATE TRIGGER trg_app_config_insert
AFTER INSERT ON app_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('app_config', 'INSERT', NEW.key);
END;

DROP TRIGGER IF EXISTS trg_app_config_update;
CREATE TRIGGER trg_app_config_update
AFTER UPDATE ON app_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('app_config', 'UPDATE', NEW.key);
END;

DROP TRIGGER IF EXISTS trg_app_config_delete;
CREATE TRIGGER trg_app_config_delete
AFTER DELETE ON app_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('app_config', 'DELETE', OLD.key);
END;

-- =========================
-- 8. restore_failures TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_restore_failures_insert;
CREATE TRIGGER trg_restore_failures_insert
AFTER INSERT ON restore_failures
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('restore_failures', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_restore_failures_update;
CREATE TRIGGER trg_restore_failures_update
AFTER UPDATE ON restore_failures
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('restore_failures', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_restore_failures_delete;
CREATE TRIGGER trg_restore_failures_delete
AFTER DELETE ON restore_failures
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('restore_failures', 'DELETE', OLD.id);
END;

-- =========================
-- 9. user_config TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_user_config_insert;
CREATE TRIGGER trg_user_config_insert
AFTER INSERT ON user_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_config', 'INSERT', NEW.user_id || ':' || NEW.key);
END;

DROP TRIGGER IF EXISTS trg_user_config_update;
CREATE TRIGGER trg_user_config_update
AFTER UPDATE ON user_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_config', 'UPDATE', NEW.user_id || ':' || NEW.key);
END;

DROP TRIGGER IF EXISTS trg_user_config_delete;
CREATE TRIGGER trg_user_config_delete
AFTER DELETE ON user_config
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('user_config', 'DELETE', OLD.user_id || ':' || OLD.key);
END;

-- =========================
-- 10. patch_apply_log TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_patch_apply_log_insert;
CREATE TRIGGER trg_patch_apply_log_insert
AFTER INSERT ON patch_apply_log
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('patch_apply_log', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_patch_apply_log_update;
CREATE TRIGGER trg_patch_apply_log_update
AFTER UPDATE ON patch_apply_log
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('patch_apply_log', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_patch_apply_log_delete;
CREATE TRIGGER trg_patch_apply_log_delete
AFTER DELETE ON patch_apply_log
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('patch_apply_log', 'DELETE', OLD.id);
END;

-- =========================
-- 11. model_whitelist TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_model_whitelist_insert;
CREATE TRIGGER trg_model_whitelist_insert
AFTER INSERT ON model_whitelist
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_model_whitelist_update;
CREATE TRIGGER trg_model_whitelist_update
AFTER UPDATE ON model_whitelist
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_model_whitelist_delete;
CREATE TRIGGER trg_model_whitelist_delete
AFTER DELETE ON model_whitelist
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist', 'DELETE', OLD.id);
END;

-- =========================
-- 12. model_whitelist_rule TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_model_whitelist_rule_insert;
CREATE TRIGGER trg_model_whitelist_rule_insert
AFTER INSERT ON model_whitelist_rule
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist_rule', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_model_whitelist_rule_update;
CREATE TRIGGER trg_model_whitelist_rule_update
AFTER UPDATE ON model_whitelist_rule
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist_rule', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_model_whitelist_rule_delete;
CREATE TRIGGER trg_model_whitelist_rule_delete
AFTER DELETE ON model_whitelist_rule
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('model_whitelist_rule', 'DELETE', OLD.id);
END;

-- =========================
-- 13. validated_device TABLE
-- =========================

DROP TRIGGER IF EXISTS trg_validated_device_insert;
CREATE TRIGGER trg_validated_device_insert
AFTER INSERT ON validated_device
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('validated_device', 'INSERT', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_validated_device_update;
CREATE TRIGGER trg_validated_device_update
AFTER UPDATE ON validated_device
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('validated_device', 'UPDATE', NEW.id);
END;

DROP TRIGGER IF EXISTS trg_validated_device_delete;
CREATE TRIGGER trg_validated_device_delete
AFTER DELETE ON validated_device
BEGIN
    INSERT INTO sync_log(table_name, operation, record_id)
    VALUES('validated_device', 'DELETE', OLD.id);
END;