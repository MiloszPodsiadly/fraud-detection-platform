package com.frauddetection.simulator.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.simulator.api.ReplaySourceType;
import com.frauddetection.simulator.config.SyntheticReplayProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class SyntheticReplayDataSource implements ReplayDataSource {

    private static final int SCENARIO_CYCLE = 1_000;

    private static final String[] HOME_COUNTRIES = {"US", "GB", "DE", "PL", "FR", "NL"};
    private static final String[] HIGH_RISK_COUNTRIES = {"BR", "NG", "RO", "MX"};
    private static final String[] NORMAL_CATEGORIES = {"Groceries", "Fuel", "Retail", "Travel", "Electronics"};
    private static final String[] NORMAL_MCC = {"5411", "5541", "5311", "4511", "5732"};
    private static final String[] RISK_CATEGORIES = {"Digital Goods", "Gift Cards", "Crypto Exchange", "Remote Services"};
    private static final String[] RISK_MCC = {"5815", "5947", "6051", "7399"};

    private final SyntheticReplayProperties syntheticReplayProperties;

    public SyntheticReplayDataSource(SyntheticReplayProperties syntheticReplayProperties) {
        this.syntheticReplayProperties = syntheticReplayProperties;
    }

    @Override
    public ReplaySourceType sourceType() {
        return ReplaySourceType.SYNTHETIC;
    }

    @Override
    public Stream<TransactionRawEvent> stream(int maxEvents) {
        String runId = Long.toString(Instant.now().toEpochMilli(), 36);
        return IntStream.range(0, maxEvents)
                .mapToObj(index -> buildEvent(index, runId));
    }

    private TransactionRawEvent buildEvent(int index, String runId) {
        SplittableRandom random = new SplittableRandom(7_341L + index);
        SyntheticScenario scenario = scenarioFor(index);
        boolean rapidTransfer = scenario == SyntheticScenario.RAPID_TRANSFER_SEED
                || scenario == SyntheticScenario.RAPID_TRANSFER_BURST;
        boolean elevatedRisk = scenario != SyntheticScenario.NORMAL && !rapidTransfer;
        boolean suspicious = elevatedRisk || scenario == SyntheticScenario.RAPID_TRANSFER_BURST;
        boolean criticalRisk = scenario == SyntheticScenario.CRITICAL_ACCOUNT_TAKEOVER;
        int customerNumber = rapidTransfer
                ? (index / 100) % Math.max(syntheticReplayProperties.customerCount(), 1) + 1
                : random.nextInt(Math.max(syntheticReplayProperties.customerCount(), 1)) + 1;
        int merchantNumber = random.nextInt(Math.max(syntheticReplayProperties.merchantCount(), 1)) + 1;

        String customerId = "syn-customer-" + customerNumber;
        String accountId = "syn-account-" + customerNumber;
        String paymentInstrumentId = "syn-card-" + customerNumber;
        String homeCountry = HOME_COUNTRIES[customerNumber % HOME_COUNTRIES.length];
        String deviceBase = "syn-device-" + customerNumber;
        String trustedDeviceId = deviceBase + "-primary";
        String fallbackDeviceId = deviceBase + "-backup";
        String deviceId = switch (scenario) {
            case NORMAL, RAPID_TRANSFER_SEED, RAPID_TRANSFER_BURST -> index % 5 == 0 ? fallbackDeviceId : trustedDeviceId;
            case MEDIUM_NEW_DEVICE, HIGH_PROXY_PURCHASE, COUNTRY_MISMATCH -> "syn-device-new-" + (index + 1);
            case CRITICAL_ACCOUNT_TAKEOVER -> "syn-device-ato-" + (index + 1);
        };
        boolean countryMismatch = scenario == SyntheticScenario.COUNTRY_MISMATCH || criticalRisk;
        String countryCode = countryMismatch ? HIGH_RISK_COUNTRIES[index % HIGH_RISK_COUNTRIES.length] : homeCountry;
        String merchantCategory = elevatedRisk ? RISK_CATEGORIES[index % RISK_CATEGORIES.length] : NORMAL_CATEGORIES[merchantNumber % NORMAL_CATEGORIES.length];
        String merchantCategoryCode = elevatedRisk ? RISK_MCC[index % RISK_MCC.length] : NORMAL_MCC[merchantNumber % NORMAL_MCC.length];
        String transactionType = elevatedRisk ? "CARD_NOT_PRESENT_PURCHASE" : "CARD_PURCHASE";
        String authorizationMethod = elevatedRisk ? "STEP_UP_CHALLENGE" : "3DS";
        boolean trustedDevice = scenario == SyntheticScenario.NORMAL && trustedDeviceId.equals(deviceId);
        boolean proxyDetected = scenario == SyntheticScenario.MEDIUM_NEW_DEVICE || scenario == SyntheticScenario.HIGH_PROXY_PURCHASE || criticalRisk;
        boolean vpnDetected = criticalRisk || (scenario == SyntheticScenario.HIGH_PROXY_PURCHASE && index % 2 == 0);
        boolean cardPresent = scenario == SyntheticScenario.NORMAL && index % 3 != 0;
        BigDecimal amount = amountForScenario(random, scenario, index);
        int bucket = index % SCENARIO_CYCLE;
        Instant transactionTimestamp = rapidTransfer
                ? syntheticReplayProperties.referenceInstant()
                        .plusSeconds((long) (index / SCENARIO_CYCLE) * 60L)
                        .plusSeconds((long) Math.max(bucket - 993, 0) * 20L)
                : syntheticReplayProperties.referenceInstant()
                        .plusSeconds((long) index * syntheticReplayProperties.secondsBetweenEvents())
                        .plusSeconds(random.nextInt(15));

        return new TransactionRawEvent(
                UUID.randomUUID().toString(),
                "syn-txn-" + runId + "-" + (index + 1),
                UUID.randomUUID().toString(),
                customerId,
                accountId,
                paymentInstrumentId,
                Instant.now(),
                transactionTimestamp,
                new Money(amount, rapidTransfer ? "PLN" : homeCurrency(homeCountry)),
                new MerchantInfo(
                        "syn-merchant-" + merchantNumber,
                        elevatedRisk ? "Risk Merchant " + merchantNumber : "Trusted Merchant " + merchantNumber,
                        merchantCategoryCode,
                        merchantCategory,
                        countryCode,
                        cardPresent ? "POS" : "ECOMMERCE",
                        cardPresent,
                        Map.of(
                                "synthetic", true,
                                "riskProfile", scenario.riskProfile(),
                                "scenario", scenario.scenarioName()
                        )
                ),
                new DeviceInfo(
                        deviceId,
                        "syn-fp-" + customerNumber + "-" + (elevatedRisk ? index + 1 : "stable"),
                        elevatedRisk ? "198.51.100." + ((index % 200) + 1) : "203.0.113." + ((customerNumber % 200) + 1),
                        elevatedRisk ? "Mozilla/5.0 Synthetic Risk Browser" : "Mozilla/5.0 Synthetic Stable Browser",
                        elevatedRisk ? "ANDROID" : "IOS",
                        elevatedRisk ? "MOBILE_WEBVIEW" : "CHROME",
                        trustedDevice,
                        proxyDetected,
                        vpnDetected,
                        Map.of(
                                "synthetic", true,
                                "deviceAgeDays", elevatedRisk ? 0 : 180,
                                "scenario", scenario.scenarioName()
                        )
                ),
                new LocationInfo(
                        countryCode,
                        countryMismatch ? "Unknown Region" : "Primary Region",
                        countryMismatch ? "Unknown City" : "Known City",
                        countryMismatch ? "00000" : "10001",
                        countryMismatch ? 0.0 : 40.7128,
                        countryMismatch ? 0.0 : -74.0060,
                        countryMismatch ? "UTC" : timezoneForCountry(homeCountry),
                        countryMismatch
                ),
                new CustomerContext(
                        customerId,
                        accountId,
                        criticalRisk ? "WATCHLIST" : "STANDARD",
                        elevatedRisk ? "risk-mail.test" : "customer.example",
                        criticalRisk ? 10 : 365 + (customerNumber % 720),
                        !criticalRisk,
                        !criticalRisk,
                        homeCountry,
                        homeCurrency(homeCountry),
                        List.of(trustedDeviceId, fallbackDeviceId),
                        Map.of(
                                "synthetic", true,
                                "behaviourProfile", scenario.scenarioName(),
                                "expectedRiskLevel", scenario.expectedRiskLevel()
                        )
                ),
                transactionType,
                authorizationMethod,
                "SYNTHETIC_GENERATOR",
                "trace-syn-" + runId + "-" + (index + 1),
                Map.of(
                        "generator", "synthetic",
                        "suspicious", suspicious,
                        "scenario", scenario.scenarioName(),
                        "expectedRiskLevel", scenario.expectedRiskLevel()
                )
        );
    }

    private SyntheticScenario scenarioFor(int index) {
        int bucket = index % SCENARIO_CYCLE;
        int block = index / SCENARIO_CYCLE;
        if (bucket == 993 && block % 2 == 0) {
            return SyntheticScenario.RAPID_TRANSFER_SEED;
        }
        if (bucket == 994) {
            return SyntheticScenario.RAPID_TRANSFER_SEED;
        }
        if (bucket == 995) {
            return SyntheticScenario.RAPID_TRANSFER_BURST;
        }
        if (bucket == 996) {
            return SyntheticScenario.MEDIUM_NEW_DEVICE;
        }
        if (bucket == 997) {
            return SyntheticScenario.HIGH_PROXY_PURCHASE;
        }
        if (bucket == 998) {
            return SyntheticScenario.COUNTRY_MISMATCH;
        }
        if (bucket == 999 && block % 2 == 0) {
            return SyntheticScenario.CRITICAL_ACCOUNT_TAKEOVER;
        }
        return SyntheticScenario.NORMAL;
    }

    private BigDecimal normalAmount(SplittableRandom random) {
        return BigDecimal.valueOf(15 + (random.nextDouble() * 180))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal suspiciousAmount(SplittableRandom random) {
        return BigDecimal.valueOf(350 + (random.nextDouble() * 2400))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal amountForScenario(SplittableRandom random, SyntheticScenario scenario, int index) {
        return switch (scenario) {
            case NORMAL -> normalAmount(random);
            case RAPID_TRANSFER_SEED, RAPID_TRANSFER_BURST -> rapidTransferAmount(index);
            case MEDIUM_NEW_DEVICE -> BigDecimal.valueOf(220 + (random.nextDouble() * 520))
                    .setScale(2, RoundingMode.HALF_UP);
            case COUNTRY_MISMATCH -> BigDecimal.valueOf(180 + (random.nextDouble() * 620))
                    .setScale(2, RoundingMode.HALF_UP);
            case HIGH_PROXY_PURCHASE -> BigDecimal.valueOf(1100 + (random.nextDouble() * 650))
                    .setScale(2, RoundingMode.HALF_UP);
            case CRITICAL_ACCOUNT_TAKEOVER -> suspiciousAmount(random);
        };
    }

    private BigDecimal rapidTransferAmount(int index) {
        int bucket = index % SCENARIO_CYCLE;
        int block = index / SCENARIO_CYCLE;
        int variant = block % 4;
        BigDecimal[][] threeTransferCases = {
                {new BigDecimal("7400.00"), new BigDecimal("8600.00"), new BigDecimal("6800.00")},
                {new BigDecimal("7750.00"), new BigDecimal("8350.00"), new BigDecimal("7150.00")},
                {new BigDecimal("6900.00"), new BigDecimal("9100.00"), new BigDecimal("7450.00")},
                {new BigDecimal("8200.00"), new BigDecimal("7800.00"), new BigDecimal("7300.00")}
        };
        BigDecimal[][] twoTransferCases = {
                {new BigDecimal("11250.00"), new BigDecimal("9300.00")},
                {new BigDecimal("10400.00"), new BigDecimal("10150.00")},
                {new BigDecimal("9800.00"), new BigDecimal("10850.00")},
                {new BigDecimal("12100.00"), new BigDecimal("8450.00")}
        };

        if (block % 2 == 0) {
            int amountIndex = Math.max(0, Math.min(bucket - 993, 2));
            return threeTransferCases[variant][amountIndex];
        }

        int amountIndex = bucket == 994 ? 0 : 1;
        return twoTransferCases[variant][amountIndex];
    }

    private String homeCurrency(String countryCode) {
        return switch (countryCode) {
            case "GB" -> "GBP";
            case "PL" -> "PLN";
            default -> "EUR";
        };
    }

    private String timezoneForCountry(String countryCode) {
        return switch (countryCode) {
            case "US" -> "America/New_York";
            case "GB" -> "Europe/London";
            case "DE" -> "Europe/Berlin";
            case "PL" -> "Europe/Warsaw";
            case "FR" -> "Europe/Paris";
            default -> "Europe/Amsterdam";
        };
    }

    private enum SyntheticScenario {
        NORMAL("normal-customer-journey", "NORMAL", "LOW"),
        RAPID_TRANSFER_SEED("rapid-transfer-seed", "NORMAL", "LOW"),
        RAPID_TRANSFER_BURST("rapid-transfer-burst", "AGGREGATE_FRAUD", "HIGH"),
        MEDIUM_NEW_DEVICE("new-device-review", "ELEVATED", "MEDIUM"),
        HIGH_PROXY_PURCHASE("high-risk-proxy-purchase", "HIGH", "HIGH"),
        COUNTRY_MISMATCH("country-mismatch-review", "ELEVATED", "MEDIUM"),
        CRITICAL_ACCOUNT_TAKEOVER("account-takeover-pattern", "CRITICAL", "CRITICAL");

        private final String scenarioName;
        private final String riskProfile;
        private final String expectedRiskLevel;

        SyntheticScenario(String scenarioName, String riskProfile, String expectedRiskLevel) {
            this.scenarioName = scenarioName;
            this.riskProfile = riskProfile;
            this.expectedRiskLevel = expectedRiskLevel;
        }

        private String scenarioName() {
            return scenarioName;
        }

        private String riskProfile() {
            return riskProfile;
        }

        private String expectedRiskLevel() {
            return expectedRiskLevel;
        }
    }
}
