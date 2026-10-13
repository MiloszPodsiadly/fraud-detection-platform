package com.frauddetection.alert.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AuditOutboxPackageArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("com.frauddetection.alert");

    @Test
    void externalIntegrityQueryBoundaryDoesNotDependOnRuntimeOrchestration() {
        noClasses()
                .that().resideInAPackage("..audit.external.integrity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..outbox..",
                        "..regulated..",
                        "..service.."
                )
                .check(classes);
    }

    @Test
    void outboxBacklogReaderDoesNotDependOnMutationOrPublicationOrchestration() {
        noClasses()
                .that().resideInAPackage("..outbox.recovery..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..regulated..",
                        "..outbox.projection..",
                        "..outbox.confirmation..",
                        "..messaging.."
                )
                .check(classes);
    }

    @Test
    void fraudCaseDecisionPoliciesDoNotDependOnMutationOrchestration() {
        noClasses()
                .that().resideInAPackage("..fraudcase.decision..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..regulated..",
                        "..outbox..",
                        "..service.."
                )
                .check(classes);
    }
}
