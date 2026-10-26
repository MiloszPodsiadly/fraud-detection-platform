package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.AnalystAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelPromotionEmergencyAvailabilityTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Test void unavailableMongoDoesNotLookLikeNoAffectedGrantsOrAValidRequest() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.find(any(), any(Class.class))).thenThrow(
                new DataAccessResourceFailureException("Mongo primary unavailable"));
        var store = mock(MongoModelPromotionStore.class);
        when(store.findProposal(any())).thenThrow(
                new DataAccessResourceFailureException("Mongo primary unavailable"));
        var service = new ModelPromotionEmergencyRetirementService(mongo, mock(TransactionTemplate.class),
                store, mock(ModelPromotionWorkflowService.class), mock(ModelPromotionAuthorization.class),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ModelPromotionEmergencyAssurancePolicy("https://trusted.example", "urn:verified:mfa", 300));
        assertThatThrownBy(() -> service.affected(authorizer(),
                new ModelPromotionEmergencyRetirementService.AffectedSelector(null, "case-12345")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> service.request(requester(), "proposal-1",
                new ModelPromotionEmergencyRetirementService.Request("grant-1", "review-1", "approval-1",
                        "case-12345", "incident-ledger:case-12345",
                        "Immediate retirement after independently confirmed incident")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private PolicyGovernanceActor authorizer() {
        return actor("authorizer", Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_AUTHORIZE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
    }

    private PolicyGovernanceActor requester() {
        return actor("requester", Set.of(AnalystAuthority.MODEL_PROMOTION_EMERGENCY_REQUEST,
                AnalystAuthority.MODEL_PROMOTION_APPROVE, AnalystAuthority.MODEL_PROMOTION_RETIRE,
                AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION));
    }

    private PolicyGovernanceActor actor(String subject, Set<String> mandates) {
        String issuer = "https://trusted.example";
        return new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, subject), mandates,
                issuer, subject, new PolicyGovernanceActor.AuthenticationEvidence("urn:verified:mfa", NOW));
    }
}
