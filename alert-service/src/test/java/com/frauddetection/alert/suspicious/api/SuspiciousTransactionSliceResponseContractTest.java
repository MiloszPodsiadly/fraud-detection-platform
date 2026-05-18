package com.frauddetection.alert.suspicious.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionSliceResponseContractTest {

    @Test
    void contentIsDefensivelyCopied() {
        ArrayList<SuspiciousTransactionResponse> content = new ArrayList<>();
        content.add(SuspiciousTransactionResponseContractTest.minimalResponse(List.of("HIGH_AMOUNT")));

        SuspiciousTransactionSliceResponse response = new SuspiciousTransactionSliceResponse(content, 20, true, "cursor-1");
        content.clear();

        assertThat(response.content()).hasSize(1);
    }

    @Test
    void nextCursorNullAllowedAndForcedWhenHasNextFalse() {
        SuspiciousTransactionSliceResponse response = new SuspiciousTransactionSliceResponse(List.of(), 20, false, "cursor-1");

        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void responseDoesNotExposePageTotalCountOrOffsetFields() {
        assertThat(Arrays.stream(SuspiciousTransactionSliceResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList())
                .containsExactly("content", "size", "hasNext", "nextCursor")
                .doesNotContain("page", "totalElements", "totalPages", "totalCount", "offset");
    }
}
