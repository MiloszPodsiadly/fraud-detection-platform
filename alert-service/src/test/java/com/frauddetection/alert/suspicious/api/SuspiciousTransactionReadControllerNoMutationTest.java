package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionReadControllerNoMutationTest {

    @Test
    void controllerHasOnlyGetMappingsAndNoMutationPaths() {
        for (Method method : SuspiciousTransactionReadController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PostMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
        }
        assertThat(Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .count()).isEqualTo(3);
        assertThat(endpointText()).doesNotContain("export", "bulk", "dismiss", "confirm", "link-case");
    }

    private String endpointText() {
        RequestMapping classMapping = SuspiciousTransactionReadController.class.getAnnotation(RequestMapping.class);
        return classMapping.value()[0] + Arrays.stream(SuspiciousTransactionReadController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> String.join(",", method.getAnnotation(GetMapping.class).value()))
                .reduce("", String::concat);
    }
}
