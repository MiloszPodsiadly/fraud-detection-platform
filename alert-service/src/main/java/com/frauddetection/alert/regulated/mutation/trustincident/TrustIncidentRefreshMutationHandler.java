package com.frauddetection.alert.regulated.mutation.trustincident;

import com.frauddetection.alert.regulated.RegulatedMutationPartialCommitException;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.trust.TrustIncidentMaterializationException;
import com.frauddetection.alert.trust.TrustIncidentMaterializationResponse;
import com.frauddetection.alert.trust.TrustIncidentMaterializer;
import com.frauddetection.alert.trust.TrustSignal;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrustIncidentRefreshMutationHandler {

    private static final String PARTIAL_REFRESH_DEGRADED = "TRUST_INCIDENT_REFRESH_PARTIAL";

    private final TrustIncidentMaterializer materializer;

    public TrustIncidentRefreshMutationHandler(TrustIncidentMaterializer materializer) {
        this.materializer = materializer;
    }

    public TrustIncidentMaterializationResponse refresh(List<TrustSignal> signals) {
        try {
            return materializer.materialize(signals);
        } catch (TrustIncidentMaterializationException exception) {
            TrustIncidentMaterializationResponse response = exception.response();
            if (response.materializedCount() <= 0) {
                throw exception;
            }
            throw new RegulatedMutationPartialCommitException(
                    PARTIAL_REFRESH_DEGRADED,
                    RegulatedMutationResponseSnapshot.fromTrustIncidentMaterialization(response),
                    exception
            );
        }
    }
}
