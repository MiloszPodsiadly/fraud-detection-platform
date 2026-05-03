package com.frauddetection.alert.regulated;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AlertServiceArchUnitTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("com.frauddetection.alert");

    @Test
    void controllersMustNotDependOnKafkaTemplate() {
        noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .check(classes);
    }

    @Test
    void requestPathServicesMustNotPublishExternalAnchorsDirectly() {
        noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher")
                .check(classes);
    }

    @Test
    void regulatedMutationHandlersMustNotPublishOrWriteSuccessAuditDirectly() {
        noClasses()
                .that().resideInAPackage("..regulated.mutation..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.frauddetection.alert.audit.AuditService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher")
                .check(classes);
    }

    @Test
    void controllersMustNotDependOnRepositoriesExceptExplicitHealthAggregationBoundary() {
        noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .and().doNotHaveFullyQualifiedName("com.frauddetection.alert.system.SystemTrustLevelController")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(classes);
    }
}
