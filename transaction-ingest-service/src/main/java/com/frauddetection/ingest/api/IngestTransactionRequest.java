package com.frauddetection.ingest.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record IngestTransactionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String transactionId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String customerId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String accountId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String paymentInstrumentId,
        @NotNull @PastOrPresent Instant transactionTimestamp,
        @NotNull @Valid MoneyRequest transactionAmount,
        @NotNull @Valid MerchantInfoRequest merchantInfo,
        @NotNull @Valid DeviceInfoRequest deviceInfo,
        @NotNull @Valid LocationInfoRequest locationInfo,
        @NotNull @Valid CustomerContextRequest customerContext,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String transactionType,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9_:-]+$") String authorizationMethod,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Z0-9_:-]+$") String sourceSystem,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:-]+$") String traceId,
        @Size(max = 50) Map<String, Object> attributes
) {
}
