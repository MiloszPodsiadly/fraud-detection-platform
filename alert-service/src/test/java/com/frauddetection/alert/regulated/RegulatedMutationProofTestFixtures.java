package com.frauddetection.alert.regulated;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RegulatedMutationProofTestFixtures {

    private RegulatedMutationProofTestFixtures() {
    }

    static RegulatedMutationDurableLocalFinalizationProof accepted() {
        RegulatedMutationDurableLocalFinalizationProof proof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(proof.verify(any())).thenReturn(DurableLocalFinalizationProofResult.accepted());
        return proof;
    }
}
