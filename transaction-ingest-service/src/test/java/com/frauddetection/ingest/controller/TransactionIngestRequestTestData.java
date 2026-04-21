package com.frauddetection.ingest.controller;

import com.frauddetection.ingest.api.CustomerContextRequest;
import com.frauddetection.ingest.api.DeviceInfoRequest;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.LocationInfoRequest;
import com.frauddetection.ingest.api.MerchantInfoRequest;
import com.frauddetection.ingest.api.MoneyRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TransactionIngestRequestTestData {

    private TransactionIngestRequestTestData() {
    }

    public static IngestTransactionRequest validRequest() {
        return new IngestTransactionRequest(
                "txn-1001",
                "cust-1001",
                "acct-1001",
                "card-1001",
                Instant.parse("2026-04-20T10:15:28Z"),
                new MoneyRequest(new BigDecimal("1249.99"), "USD"),
                new MerchantInfoRequest(
                        "merchant-1001",
                        "Northwind Electronics",
                        "5732",
                        "Electronics",
                        "US",
                        "ECOMMERCE",
                        false,
                        Map.of("merchantRiskTier", "medium")
                ),
                new DeviceInfoRequest(
                        "device-1001",
                        "fp-7ab921",
                        "203.0.113.24",
                        "Mozilla/5.0",
                        "ANDROID",
                        "CHROME",
                        false,
                        false,
                        false,
                        Map.of("emulatorDetected", false)
                ),
                new LocationInfoRequest(
                        "US",
                        "CA",
                        "San Francisco",
                        "94105",
                        37.7897,
                        -122.3942,
                        "America/Los_Angeles",
                        false
                ),
                new CustomerContextRequest(
                        "cust-1001",
                        "acct-1001",
                        "PREMIUM",
                        "example.com",
                        620,
                        true,
                        true,
                        "US",
                        "USD",
                        List.of("device-0901", "device-0902"),
                        Map.of("kycLevel", "FULL")
                ),
                "PURCHASE",
                "3DS",
                "PAYMENT_GATEWAY",
                "trace-1001",
                Map.of("channel", "mobile-app")
        );
    }
}
