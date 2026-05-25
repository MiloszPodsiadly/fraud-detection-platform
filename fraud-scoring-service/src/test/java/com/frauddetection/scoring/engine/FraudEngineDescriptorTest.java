package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineDescriptorTest {

    private static final List<String> CANONICAL_LANGUAGES = List.of(
            "java", "python", "go", "kotlin", "scala", "javascript", "other"
    );
    private static final List<String> UNSUPPORTED_LANGUAGES = List.of(
            "groovy", "rust", "c", "cpp", "csharp", "fsharp", "typescript", "ruby", "php", "swift",
            "objectivec", "dart", "elixir", "erlang", "haskell", "clojure", "lua", "perl", "r",
            "julia", "sql", "bash"
    );

    @Test
    void acceptsValidDescriptorAndStoresRequiredAsMetadata() {
        FraudEngineDescriptor descriptor = descriptor("rules.primary:v1", "java", "1.0.0", true);

        assertThat(descriptor.engineId()).isEqualTo("rules.primary:v1");
        assertThat(descriptor.engineType()).isEqualTo(FraudEngineType.RULES);
        assertThat(descriptor.engineLanguage()).isEqualTo("java");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.required()).isTrue();
    }

    @Test
    void acceptsMachineReadableEngineIds() {
        for (String engineId : List.of("rules.primary", "rules-primary", "rules_primary", "rules.primary:v1")) {
            assertThat(descriptor(engineId, "java", "1.0.0", false).engineId()).isEqualTo(engineId);
        }
    }

    @Test
    void rejectsMissingOrNonMachineReadableEngineIds() {
        assertThatThrownBy(() -> descriptor(null, "java", "1.0.0", false))
                .isInstanceOf(NullPointerException.class);

        for (String engineId : List.of("", "   ", "rules primary", "rules\nprimary", "rules\tprimary",
                "rules\u0000primary", "engine used for current rules", "r".repeat(129))) {
            assertThatThrownBy(() -> descriptor(engineId, "java", "1.0.0", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("engineId");
        }
    }

    @Test
    void rejectsMissingEngineType() {
        assertThatThrownBy(() -> new FraudEngineDescriptor("rules.primary", null, "java", "1.0.0", false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("engineType");
    }

    @Test
    void acceptsOnlyCanonicalEngineLanguages() {
        for (String language : CANONICAL_LANGUAGES) {
            assertThat(descriptor("rules.primary", language, "1.0.0", false).engineLanguage())
                    .isEqualTo(language);
        }
    }

    @Test
    void descriptorLanguagePolicyMatchesFraudEngineResultLanguagePolicy() {
        for (String language : CANONICAL_LANGUAGES) {
            assertThatCode(() -> result(language))
                    .as("FraudSignalEngine.evaluate() returns FraudEngineResult; descriptor and result language policies must stay aligned for %s", language)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsMissingAliasesCaseVariantsAndFreeTextLanguages() {
        assertThatThrownBy(() -> descriptor("rules.primary", null, "1.0.0", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("engineLanguage")
                .hasMessageContaining("canonical lowercase allowlisted language");

        for (String language : List.of(
                "", "   ", "Java", "PYTHON", "Go", "Rust", "python3", "py", "node", "nodejs", "js", "ts",
                "C++", "c++", "C#", "c#", ".NET", "dotnet", "java17", "Java 17", "python runtime",
                "rule engine v1", "java\n", "java\t", "java\u0000", "a".repeat(129)
        )) {
            assertThatThrownBy(() -> descriptor("rules.primary", language, "1.0.0", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("engineLanguage")
                    .hasMessageContaining("canonical lowercase allowlisted language");
        }

        for (String language : UNSUPPORTED_LANGUAGES) {
            assertThatThrownBy(() -> descriptor("rules.primary", language, "1.0.0", false))
                    .as("descriptor language must remain compatible with FraudEngineResult: %s", language)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("engineLanguage");
        }
    }

    @Test
    void validatesMachineReadableVersion() {
        assertThatThrownBy(() -> descriptor("rules.primary", "java", null, false))
                .isInstanceOf(NullPointerException.class);

        for (String version : List.of("", "   ", "rules v1", "v".repeat(129))) {
            assertThatThrownBy(() -> descriptor("rules.primary", "java", version, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
        }

        for (String version : List.of("1.0.0", "rules-v1", "model:v2", "engine_2026.05")) {
            assertThat(descriptor("rules.primary", "java", version, false).version()).isEqualTo(version);
        }
    }

    @Test
    void exposesOnlyStaticDescriptorComponents() {
        assertThat(Arrays.stream(FraudEngineDescriptor.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("engineId", "engineType", "engineLanguage", "version", "required")
                .doesNotContain(
                        "score", "riskLevel", "weight", "priority", "timeoutMs", "fallbackReason",
                        "statusReason", "healthUrl", "endpoint", "apiKey", "token", "secret", "metadata",
                        "configuration", "engineResults"
                );
    }

    @Test
    void keepsDescriptorValidationPolicyInternalToItsPackage() {
        assertThat(Modifier.isPublic(FraudEngineDescriptorValuePolicy.class.getModifiers())).isFalse();
    }

    private FraudEngineDescriptor descriptor(String engineId, String language, String version, boolean required) {
        return new FraudEngineDescriptor(engineId, FraudEngineType.RULES, language, version, required);
    }

    private FraudEngineResult result(String language) {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                language,
                FraudEngineStatus.AVAILABLE,
                0.1d,
                RiskLevel.LOW,
                FraudEngineConfidence.LOW,
                List.of(),
                List.of(),
                List.of(),
                1L,
                null,
                null,
                null,
                Instant.parse("2026-05-25T09:00:00Z")
        );
    }
}
