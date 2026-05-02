package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.regulated.RegulatedMutationPartialCommitException;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionMode;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.trust.TrustIncidentMaterializationException;
import com.frauddetection.alert.trust.TrustIncidentMaterializationResponse;
import com.frauddetection.alert.trust.TrustIncidentMaterializer;
import com.frauddetection.alert.trust.TrustIncidentRefreshMode;
import com.frauddetection.alert.trust.TrustIncidentRepository;
import com.frauddetection.alert.trust.TrustIncidentResponse;
import com.frauddetection.alert.trust.TrustSignal;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrustIncidentRefreshMutationHandler {

    private static final String PARTIAL_REFRESH_DEGRADED = "TRUST_INCIDENT_REFRESH_PARTIAL";

    private final TrustIncidentMaterializer materializer;
    private final TrustIncidentRepository repository;
    private final RegulatedMutationTransactionRunner transactionRunner;
    private final TrustIncidentRefreshMode refreshMode;

    public TrustIncidentRefreshMutationHandler(
            TrustIncidentMaterializer materializer,
            TrustIncidentRepository repository,
            RegulatedMutationTransactionRunner transactionRunner,
            @Value("${app.trust-incidents.refresh-mode:ATOMIC}") String refreshMode
    ) {
        this.materializer = materializer;
        this.repository = repository;
        this.transactionRunner = transactionRunner;
        this.refreshMode = TrustIncidentRefreshMode.parse(refreshMode);
    }

    public TrustIncidentMaterializationResponse refresh(List<TrustSignal> signals) {
        try {
            return materializer.materialize(signals)
                    .withOperationalState("AVAILABLE", transactionRunner.mode().name(), false, null);
        } catch (TrustIncidentMaterializationException exception) {
            TrustIncidentMaterializationResponse response = exception.response();
            if (transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED || refreshMode == TrustIncidentRefreshMode.ATOMIC) {
                throw exception;
            }
            List<TrustIncidentResponse> persistedIncidents = persistedIncidents(signals);
            if (persistedIncidents.isEmpty()) {
                throw exception;
            }
            int persistedCount = persistedIncidents.size();
            TrustIncidentMaterializationResponse verified = new TrustIncidentMaterializationResponse(
                    "PARTIAL",
                    response.signalCount(),
                    persistedCount,
                    response.requestedSignalCount(),
                    persistedCount,
                    Math.max(0, response.requestedSignalCount() - persistedCount),
                    true,
                    "PERSISTENCE_UNAVAILABLE",
                    persistedIncidents,
                    "COMMITTED_DEGRADED",
                    transactionRunner.mode().name(),
                    false,
                    "TRUST_INCIDENT_REFRESH_PARTIAL_OFF_MODE"
            );
            throw new RegulatedMutationPartialCommitException(
                    PARTIAL_REFRESH_DEGRADED,
                    RegulatedMutationResponseSnapshot.fromTrustIncidentMaterialization(verified),
                    exception
            );
        }
    }

    private List<TrustIncidentResponse> persistedIncidents(List<TrustSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<TrustIncidentResponse> incidents = new java.util.ArrayList<>();
        for (TrustSignal signal : signals) {
            repository.findByActiveDedupeKey(activeDedupeKey(signal))
                    .map(TrustIncidentResponse::from)
                    .ifPresent(incidents::add);
        }
        return List.copyOf(incidents);
    }

    private String activeDedupeKey(TrustSignal signal) {
        return signal.type() + ":" + signal.source() + ":" + fingerprint(signal);
    }

    private String fingerprint(TrustSignal signal) {
        return signal.fingerprint() == null || signal.fingerprint().isBlank()
                ? RegulatedMutationIntentHasher.hash(signal.type() + "|" + signal.source())
                : RegulatedMutationIntentHasher.hash(signal.fingerprint());
    }
}
