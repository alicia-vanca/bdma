package com.app.common.repositories;

import com.app.common.models.ModelWhitelist;
import com.app.common.models.ModelWhitelistRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ModelWhitelistRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelWhitelistRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private ModelWhitelist mapWhitelistRow(ResultSet rs, int rowNum) throws SQLException {
        ModelWhitelist whitelist = new ModelWhitelist();
        whitelist.setId(rs.getLong("id"));
        whitelist.setModelName(rs.getString("model_name"));
        long serviceRuleSetId = rs.getLong("service_rule_set_id");
        whitelist.setServiceRuleSetId(rs.wasNull() ? null : serviceRuleSetId);
        whitelist.setActive(rs.getInt("is_active") == 1);
        whitelist.setCreatedAt(rs.getString("created_at"));
        whitelist.setRules(new ArrayList<>());
        return whitelist;
    }

    private ModelWhitelistRule mapRuleRow(ResultSet rs, int rowNum) throws SQLException {
        ModelWhitelistRule rule = new ModelWhitelistRule();
        rule.setId(rs.getLong("id"));
        rule.setWhitelistId(rs.getLong("whitelist_id"));
        rule.setPropKey(rs.getString("prop_key"));
        rule.setExpectedValue(rs.getString("expected_value"));
        return rule;
    }

    public List<ModelWhitelist> findAllActiveWithRules() {
        List<ModelWhitelist> whitelists = jdbcTemplate.query(
                "SELECT id, model_name, service_rule_set_id, is_active, created_at FROM model_whitelist WHERE is_active = 1 ORDER BY id",
                this::mapWhitelistRow);

        if (whitelists.isEmpty()) {
            return whitelists;
        }

        Map<Long, ModelWhitelist> byId = new LinkedHashMap<>();
        for (ModelWhitelist whitelist : whitelists) {
            byId.put(whitelist.getId(), whitelist);
        }

        List<ModelWhitelistRule> rules = jdbcTemplate.query(
                "SELECT id, whitelist_id, prop_key, expected_value FROM model_whitelist_rule ORDER BY whitelist_id, id",
                this::mapRuleRow);

        for (ModelWhitelistRule rule : rules) {
            ModelWhitelist parent = byId.get(rule.getWhitelistId());
            if (parent != null) {
                parent.getRules().add(rule);
            }
        }

        return whitelists;
    }
}
