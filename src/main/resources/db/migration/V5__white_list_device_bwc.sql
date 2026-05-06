INSERT OR IGNORE INTO model_whitelist (id, model_name, is_active)
VALUES ('body_camera_bwc', 'BWC', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.product.model', 'BWC');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.product.device', 'k69v1_64_k419');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.board.platform', 'mt6768');