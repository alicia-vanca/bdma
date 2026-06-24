package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.definitions.enums.ServiceRuleAction;
import com.app.common.definitions.enums.ServiceRulePhase;
import com.app.common.models.ServiceRule;
import com.app.common.models.ServiceRuleSet;

@Repository
public class ServiceRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(ServiceRuleRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ServiceRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private ServiceRuleSet mapRuleSetRow(ResultSet rs, int rowNum) throws SQLException {
        ServiceRuleSet ruleSet = new ServiceRuleSet();
        ruleSet.setId(rs.getLong("id"));
        ruleSet.setName(rs.getString("name"));
        ruleSet.setActive(rs.getBoolean("is_active"));
        ruleSet.setCreatedAt(rs.getString("created_at"));
        return ruleSet;
    }

    private ServiceRule mapRuleRow(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        String phase = rs.getString("phase");
        String action = rs.getString("action");

        try {
            ServiceRule rule = new ServiceRule();
            rule.setId(id);
            rule.setRuleSetId(rs.getLong("rule_set_id"));
            rule.setPhase(ServiceRulePhase.valueOf(phase));
            rule.setExecuteOrder(rs.getInt("execute_order"));
            rule.setAction(ServiceRuleAction.valueOf(action));
            rule.setServiceName(rs.getString("service_name"));
            rule.setActive(rs.getBoolean("is_active"));
            return rule;
        } catch (IllegalArgumentException e) {
            log.warn("Skipping service rule with invalid enum value: id={}, phase={}, action={}", id, phase, action, e);
            return null;
        }
    }

    /**
     * Finds the rule set ID assigned directly to an active validated model
     * whitelist.
     *
     * @param whitelistId validated model whitelist identifier
     * @return assigned rule set ID, or empty when model uses default rules
     */
    public Optional<Long> findAssignedRuleSetIdByWhitelistId(Long whitelistId) {
        if (whitelistId == null) {
            return Optional.empty();
        }

        String sql = """
                SELECT service_rule_set_id
                FROM model_whitelist
                WHERE id = ?
                  AND is_active = TRUE
                  AND service_rule_set_id IS NOT NULL
                LIMIT 1
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, Long.class, whitelistId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Finds an active service rule set by ID.
     *
     * @param ruleSetId service rule set identifier
     * @return active rule set, or empty when missing or inactive
     */
    public Optional<ServiceRuleSet> findActiveRuleSetById(Long ruleSetId) {
        if (ruleSetId == null) {
            return Optional.empty();
        }

        String sql = """
                SELECT id, name, is_active, created_at
                FROM service_rule_set
                WHERE id = ?
                  AND is_active = TRUE
                LIMIT 1
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRuleSetRow, ruleSetId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Finds the reserved active default rule set used by whitelisted models without
     * assigned rule sets.
     *
     * @return active default rule set, or empty when database seed is missing
     */
    public Optional<ServiceRuleSet> findActiveDefaultRuleSet() {
        String sql = """
                SELECT id, name, is_active, created_at
                FROM service_rule_set
                WHERE name = 'default'
                  AND is_active = TRUE
                LIMIT 1
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRuleSetRow));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Loads active rules in deterministic execution order for a phase.
     *
     * @param ruleSetId service rule set identifier
     * @param phase     sync phase to execute
     * @return ordered active rules
     */
    public List<ServiceRule> findActiveRules(Long ruleSetId, ServiceRulePhase phase) {
        String sql = """
                SELECT id, rule_set_id, phase, execute_order, action, service_name, is_active
                FROM service_rule
                WHERE rule_set_id = ?
                  AND phase = ?
                  AND is_active = TRUE
                ORDER BY execute_order, id
                """;
        return jdbcTemplate.query(sql, this::mapRuleRow, ruleSetId, phase.name())
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
