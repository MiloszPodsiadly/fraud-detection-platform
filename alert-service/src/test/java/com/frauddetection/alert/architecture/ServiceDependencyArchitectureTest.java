package com.frauddetection.alert.architecture;

import com.frauddetection.alert.feedback.validation.fixture.FeedbackValidationWriteDependencyViolation;
import com.frauddetection.alert.fraudcase.decision.fixture.FraudCaseDecisionWriteDependencyViolation;
import com.frauddetection.alert.outbox.recovery.fixture.OutboxRecoveryWriteDependencyViolation;
import com.frauddetection.alert.system.trustlevel.application.fixture.TrustApplicationHttpDependencyViolation;
import com.frauddetection.alert.system.trustlevel.health.fixture.TrustHealthHttpDependencyViolation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceDependencyArchitectureTest {

    private static final ArchRule TRUST_HEALTH_ISOLATION = noClasses()
            .that().resideInAPackage("..system.trustlevel.health..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..system.trustlevel.api..",
                    "..alert.api..",
                    "..controller..",
                    "org.springframework.web..",
                    "jakarta.servlet.."
            )
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    private static final ArchRule TRUST_APPLICATION_ISOLATION = noClasses()
            .that().resideInAPackage("..system.trustlevel.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..system.trustlevel.api..",
                    "..alert.api..",
                    "..controller..",
                    "org.springframework.web..",
                    "jakarta.servlet.."
            )
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    private static final ArchRule OUTBOX_RECOVERY_READ_ISOLATION = noClasses()
            .that().resideInAPackage("..outbox.recovery..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Coordinator")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Publisher")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Writer")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("MutationHandler")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("RecoveryService")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");

    private static final ArchRule FRAUD_CASE_DECISION_ISOLATION = noClasses()
            .that().resideInAPackage("..fraudcase.decision..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.frauddetection.alert.audit.AuditService")
            .orShould().dependOnClassesThat().resideInAPackage("..outbox..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Writer")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Publisher")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");

    private static final ArchRule FEEDBACK_PURE_TRANSFORMATION_ISOLATION = noClasses()
            .that().resideInAnyPackage("..feedback.validation..", "..feedback.assembly..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.frauddetection.alert.audit.AuditService")
            .orShould().dependOnClassesThat().resideInAPackage("..outbox..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Writer")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Publisher")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate");

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.frauddetection.alert");

    @Test
    void productionBoundariesRemainIsolated() {
        TRUST_HEALTH_ISOLATION.check(productionClasses);
        TRUST_APPLICATION_ISOLATION.check(productionClasses);
        OUTBOX_RECOVERY_READ_ISOLATION.check(productionClasses);
        FRAUD_CASE_DECISION_ISOLATION.check(productionClasses);
        FEEDBACK_PURE_TRANSFORMATION_ISOLATION.check(productionClasses);
    }

    @Test
    void trustHealthRuleRejectsHttpDependency() {
        assertViolation(TRUST_HEALTH_ISOLATION, TrustHealthHttpDependencyViolation.class);
    }

    @Test
    void trustApplicationRuleRejectsHttpDtoDependency() {
        assertViolation(TRUST_APPLICATION_ISOLATION, TrustApplicationHttpDependencyViolation.class);
    }

    @Test
    void outboxRecoveryRuleRejectsPublisherDependency() {
        assertViolation(OUTBOX_RECOVERY_READ_ISOLATION, OutboxRecoveryWriteDependencyViolation.class);
    }

    @Test
    void fraudCaseDecisionRuleRejectsAuditMutationDependency() {
        assertViolation(FRAUD_CASE_DECISION_ISOLATION, FraudCaseDecisionWriteDependencyViolation.class);
    }

    @Test
    void feedbackRuleRejectsRepositoryDependency() {
        assertViolation(FEEDBACK_PURE_TRANSFORMATION_ISOLATION, FeedbackValidationWriteDependencyViolation.class);
    }

    private void assertViolation(ArchRule rule, Class<?> fixture) {
        JavaClasses violatingClasses = new ClassFileImporter().importClasses(fixture);
        assertThatThrownBy(() -> rule.check(violatingClasses))
                .isInstanceOf(AssertionError.class);
    }
}
