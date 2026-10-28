package com.frauddetection.scoring.orchestration;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.device.DeviceRiskSignalReasonCode;
import com.frauddetection.scoring.engine.ml.PythonMlSignalReasonCode;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalReasonCode;
import com.frauddetection.scoring.engine.velocity.VelocitySignalReasonCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineReasonCodeOwnershipProducerTest {

    @Test
    void actualProducerReasonCodesAreAllowedForTheirEmittingEngine() {
        assertOwnedBy(FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID, Stream.concat(
                Arrays.stream(RuleBasedSignalReasonCode.values()).map(RuleBasedSignalReasonCode::wireValue),
                Stream.of(
                        ReasonCode.DEVICE_NOVELTY,
                        ReasonCode.COUNTRY_MISMATCH,
                        ReasonCode.PROXY_OR_VPN,
                        ReasonCode.MERCHANT_CONCENTRATION,
                        ReasonCode.HIGH_VELOCITY,
                        ReasonCode.HIGH_AMOUNT_ACTIVITY,
                        ReasonCode.RAPID_PLN_20K_BURST,
                        ReasonCode.HIGH_TRANSACTION_AMOUNT
                ).map(ReasonCode::wireValue)
        ));

        assertOwnedBy(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, Stream.concat(
                Arrays.stream(PythonMlSignalReasonCode.values()).map(PythonMlSignalReasonCode::wireValue),
                Stream.of(
                        // Keep aligned with ml-inference-service/app/inference/model_runtime.py.
                        "RECENT_TRANSACTION_SPIKE",
                        "RECENT_AMOUNT_ACCUMULATION",
                        "TRANSACTION_VELOCITY",
                        "MERCHANT_CONCENTRATION",
                        "DEVICE_NOVELTY",
                        "COUNTRY_MISMATCH",
                        "PROXY_OR_VPN",
                        "MODEL_HIGH_RISK",
                        "RAPID_PLN_20K_BURST"
                )
        ));

        assertOwnedBy(FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID, Stream.concat(
                Arrays.stream(VelocitySignalReasonCode.values()).map(VelocitySignalReasonCode::wireValue),
                Stream.of(
                        ReasonCode.RAPID_PLN_20K_BURST,
                        ReasonCode.TRANSACTION_VELOCITY,
                        ReasonCode.RECENT_AMOUNT_ACCUMULATION
                ).map(ReasonCode::wireValue)
        ));

        assertOwnedBy(FraudEngineIdentityContract.DEVICE_PRIMARY_ENGINE_ID,
                Arrays.stream(DeviceRiskSignalReasonCode.values()).map(DeviceRiskSignalReasonCode::wireValue));

        List<String> engineIds = FraudEngineIdentityContract.engineOrder();
        for (OrchestrationFailureReasonCode reasonCode : OrchestrationFailureReasonCode.values()) {
            for (String engineId : engineIds) {
                assertThat(EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engineId, reasonCode.wireValue()))
                        .as("%s is allowed for %s", reasonCode, engineId)
                        .isTrue();
            }
        }
    }

    private void assertOwnedBy(String engineId, Stream<String> emittedReasonCodes) {
        emittedReasonCodes.forEach(reasonCode -> assertThat(
                EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engineId, reasonCode)
        ).as("%s is allowed for its producer %s", reasonCode, engineId).isTrue());
    }
}
