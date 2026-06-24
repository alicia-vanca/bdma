package com.app.common.services;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.definitions.enums.ServiceRulePhase;
import com.app.common.models.ServiceRule;
import com.app.common.models.ServiceRuleSet;
import com.app.common.repositories.ServiceRuleRepository;

@Service
public class DeviceServiceRuleService {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceRuleService.class);
    private static final String DEFAULT_RULE_SET_NAME = "default";

    private final ServiceRuleRepository serviceRuleRepository;

    public DeviceServiceRuleService(ServiceRuleRepository serviceRuleRepository) {
        this.serviceRuleRepository = serviceRuleRepository;
    }

    /**
     * Resolves service rules for a whitelist model. Assigned active rule sets win;
     * models without assignment use the active default rule set. Assigned inactive
     * or missing rule sets intentionally execute no rules.
     *
     * @param whitelistId model whitelist identifier
     * @param phase       sync phase to execute
     * @return active service rules in execution order
     */
    public List<ServiceRule> resolveRules(Long whitelistId, ServiceRulePhase phase) {
        Optional<Long> assignedRuleSetId = serviceRuleRepository.findAssignedRuleSetIdByWhitelistId(whitelistId);
        if (assignedRuleSetId.isPresent()) {
            Optional<ServiceRuleSet> assignedRuleSet = serviceRuleRepository
                    .findActiveRuleSetById(assignedRuleSetId.get());
            if (assignedRuleSet.isEmpty()) {
                log.warn(
                        "Assigned service control rule set inactive or unavailable; no service rules executed: whitelistId={}, phase={}, assignedRuleSetId={}",
                        whitelistId, phase, assignedRuleSetId.get());
                return List.of();
            }

            return loadRules(whitelistId, phase, assignedRuleSet.get(), true);
        }

        Optional<ServiceRuleSet> defaultRuleSet = serviceRuleRepository.findActiveDefaultRuleSet();
        if (defaultRuleSet.isEmpty()) {
            log.warn("No active default service control rule set found: whitelistId={}, phase={}",
                    whitelistId,
                    phase);
            return List.of();
        }

        return loadRules(whitelistId, phase, defaultRuleSet.get(), false);
    }

    private List<ServiceRule> loadRules(Long whitelistId, ServiceRulePhase phase, ServiceRuleSet ruleSet,
            boolean explicitlySpecified) {
        List<ServiceRule> rules = serviceRuleRepository.findActiveRules(ruleSet.getId(), phase);
        if (rules == null) {
            log.warn(
                    "Service control rule lookup returned null; using empty rule list: whitelistId={}, phase={}, ruleSetId={}, ruleSetName={}, explicitlySpecified={}",
                    whitelistId, phase, ruleSet.getId(), ruleSet.getName(), explicitlySpecified);
            rules = List.of();
        }

        String selection = DEFAULT_RULE_SET_NAME.equals(ruleSet.getName()) ? DEFAULT_RULE_SET_NAME : "specified";
        log.info(
                "Loaded {} service control rule set with {} active rule(s): whitelistId={}, phase={}, ruleSetId={}, ruleSetName={}, explicitlySpecified={}",
                selection, rules.size(), whitelistId, phase, ruleSet.getId(), ruleSet.getName(), explicitlySpecified);
        return rules;
    }
}
