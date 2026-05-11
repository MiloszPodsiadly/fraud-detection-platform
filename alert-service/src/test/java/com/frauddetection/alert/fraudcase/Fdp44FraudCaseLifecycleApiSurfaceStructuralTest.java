package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.AssignFraudCaseRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.ReopenFraudCaseRequest;
import com.frauddetection.alert.api.TransitionFraudCaseRequest;
import com.frauddetection.alert.api.UpdateFraudCaseRequest;
import com.frauddetection.alert.controller.FraudCaseController;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.service.FraudCaseLifecycleService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp44FraudCaseLifecycleApiSurfaceStructuralTest {

    @Test
    void fraudCaseControllerLifecycleMutationsRequireIdempotencyHeader() {
        List<String> lifecycleMethods = List.of(
                "createCase",
                "assign",
                "addNote",
                "addDecision",
                "transition",
                "close",
                "reopen",
                "updateCase"
        );

        for (String methodName : lifecycleMethods) {
            Method method = Arrays.stream(FraudCaseController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();

            assertThat(Arrays.stream(method.getParameters()).anyMatch(this::isRequiredIdempotencyHeader))
                    .as(methodName + " must require X-Idempotency-Key")
                    .isTrue();
        }
    }

    @Test
    void publicLifecycleMutationServicesExposeOnlyIdempotentOverloads() throws Exception {
        assertNoPublicNoKeyLifecycleOverloads(FraudCaseLifecycleService.class);
        assertNoPublicNoKeyLifecycleOverloads(FraudCaseManagementService.class);

        assertThat(FraudCaseManagementService.class.getMethod("updateCase", String.class, UpdateFraudCaseRequest.class, String.class))
                .isNotNull();
        assertThat(Arrays.stream(FraudCaseManagementService.class.getMethods())
                .filter(method -> method.getName().equals("updateCase"))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .noneMatch(method -> method.getParameterCount() == 2))
                .isTrue();
    }

    @Test
    void lifecycleIdempotencyIsRequiredByConstructionAndNotCoordinatorBacked() {
        assertThat(FraudCaseLifecycleIdempotencyService.class).hasAnnotation(Service.class);

        Constructor<?>[] publicConstructors = FraudCaseLifecycleService.class.getConstructors();
        assertThat(publicConstructors).hasSize(1);
        assertThat(Arrays.asList(publicConstructors[0].getParameterTypes()))
                .contains(FraudCaseLifecycleIdempotencyService.class, FraudCaseResponseMapper.class)
                .doesNotContain(RegulatedMutationCoordinator.class);
        assertThat(Arrays.stream(publicConstructors)
                .map(Constructor::getParameterTypes)
                .allMatch(parameters -> Arrays.asList(parameters).contains(FraudCaseLifecycleIdempotencyService.class)))
                .isTrue();
        assertThat(Arrays.stream(FraudCaseLifecycleService.class.getConstructors())
                .noneMatch(constructor -> Arrays.asList(constructor.getParameterTypes()).contains(RegulatedMutationCoordinator.class)))
                .isTrue();

        assertNoFieldOfType(FraudCaseLifecycleService.class, RegulatedMutationCoordinator.class);
        assertNoFieldOfType(FraudCaseLifecycleIdempotencyService.class, RegulatedMutationCoordinator.class);
        assertNoConstructorParameterOfType(FraudCaseLifecycleIdempotencyService.class, RegulatedMutationCoordinator.class);
    }

    @Test
    void lifecycleServicePublicConstructorUsesInjectedResponseMapperOnly() throws Exception {
        String source = Files.readString(sourceRoot().resolve(Path.of("service", "FraudCaseLifecycleService.java")));

        assertThat(source)
                .contains("FraudCaseResponseMapper responseMapper")
                .doesNotContain("new FraudCaseResponseMapper(")
                .doesNotContain("new AlertResponseMapper(");
        assertNoConstructorParameterOfType(FraudCaseLifecycleService.class, AlertResponseMapper.class);
    }

    @Test
    void controllerSourceCallsOnlyIdempotencyKeyLifecycleOverloads() throws Exception {
        String source = Files.readString(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source)
                .contains("fraudCaseManagementService.createCase(request, idempotencyKey)")
                .contains("fraudCaseManagementService.assignCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.addNote(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.addDecision(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.transitionCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.closeCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.reopenCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.updateCase(caseId, request, idempotencyKey)")
                .doesNotContain("fraudCaseManagementService.createCase(request)")
                .doesNotContain("fraudCaseManagementService.assignCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.addNote(caseId, request)")
                .doesNotContain("fraudCaseManagementService.addDecision(caseId, request)")
                .doesNotContain("fraudCaseManagementService.transitionCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.closeCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.reopenCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.updateCase(caseId, request)");
    }

    private boolean isRequiredIdempotencyHeader(Parameter parameter) {
        RequestHeader header = parameter.getAnnotation(RequestHeader.class);
        return header != null
                && "X-Idempotency-Key".equals(header.name())
                && header.required();
    }

    private void assertNoPublicNoKeyLifecycleOverloads(Class<?> type) {
        assertNoPublicNoKeyMethod(type, "createCase", CreateFraudCaseRequest.class);
        assertNoPublicNoKeyMethod(type, "assignCase", String.class, AssignFraudCaseRequest.class);
        assertNoPublicNoKeyMethod(type, "addNote", String.class, AddFraudCaseNoteRequest.class);
        assertNoPublicNoKeyMethod(type, "addDecision", String.class, AddFraudCaseDecisionRequest.class);
        assertNoPublicNoKeyMethod(type, "transitionCase", String.class, TransitionFraudCaseRequest.class);
        assertNoPublicNoKeyMethod(type, "closeCase", String.class, CloseFraudCaseRequest.class);
        assertNoPublicNoKeyMethod(type, "reopenCase", String.class, ReopenFraudCaseRequest.class);
    }

    private void assertNoPublicNoKeyMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        boolean present = Arrays.stream(type.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .anyMatch(method -> method.getName().equals(name)
                        && Arrays.equals(method.getParameterTypes(), parameterTypes));

        assertThat(present)
                .as(type.getSimpleName() + "." + name + " must require an idempotency key")
                .isFalse();
    }

    private void assertNoFieldOfType(Class<?> type, Class<?> forbidden) {
        assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getType))
                .doesNotContain(forbidden);
    }

    private void assertNoConstructorParameterOfType(Class<?> type, Class<?> forbidden) {
        assertThat(Arrays.stream(type.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .doesNotContain(forbidden);
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
