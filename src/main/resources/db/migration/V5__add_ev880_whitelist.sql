-- Add new model whitelist.
INSERT OR IGNORE INTO model_whitelist (model_name, is_active)
VALUES ('EV880', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT whitelist.id,
       rules.prop_key,
       CASE rules.prop_key
           WHEN 'ro.product.model' THEN whitelist.model_name
           WHEN 'ro.product.device' THEN 'DSJ-HYTH7A1'
           WHEN 'ro.board.platform' THEN 'msm8953'
       END
FROM model_whitelist whitelist
JOIN (
    SELECT 'ro.product.model' AS prop_key
    UNION ALL SELECT 'ro.product.device'
    UNION ALL SELECT 'ro.board.platform'
) rules
WHERE whitelist.model_name = 'EV880';
