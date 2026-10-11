package com.frauddetection.alert.system.trustlevel;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SystemTrustLevelArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.frauddetection.alert.system.trustlevel");

    @Test
    void applicationBoundaryDoesNotDependOnHttpApi() {
        noClasses()
                .that().resideInAPackage("..system.trustlevel.application..")
                .should().dependOnClassesThat().resideInAPackage("..system.trustlevel.api..")
                .check(classes);
    }
}
