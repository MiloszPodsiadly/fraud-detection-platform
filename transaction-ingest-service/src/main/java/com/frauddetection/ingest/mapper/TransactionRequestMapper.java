package com.frauddetection.ingest.mapper;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.ingest.api.CustomerContextRequest;
import com.frauddetection.ingest.api.DeviceInfoRequest;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.LocationInfoRequest;
import com.frauddetection.ingest.api.MerchantInfoRequest;
import com.frauddetection.ingest.api.MoneyRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class TransactionRequestMapper {

    public TransactionRawEvent toEvent(IngestTransactionRequest request, String correlationId) {
        return new TransactionRawEvent(
                UUID.randomUUID().toString(),
                request.transactionId(),
                correlationId,
                request.customerId(),
                request.accountId(),
                request.paymentInstrumentId(),
                Instant.now(),
                request.transactionTimestamp(),
                toMoney(request.transactionAmount()),
                toMerchantInfo(request.merchantInfo()),
                toDeviceInfo(request.deviceInfo()),
                toLocationInfo(request.locationInfo()),
                toCustomerContext(request.customerContext()),
                request.transactionType(),
                request.authorizationMethod(),
                request.sourceSystem(),
                request.traceId(),
                defaultMap(request.attributes())
        );
    }

    private Money toMoney(MoneyRequest request) {
        return new Money(request.amount(), request.currency());
    }

    private MerchantInfo toMerchantInfo(MerchantInfoRequest request) {
        return new MerchantInfo(
                request.merchantId(),
                request.merchantName(),
                request.merchantCategoryCode(),
                request.merchantCategory(),
                request.acquiringCountryCode(),
                request.channel(),
                request.cardPresent(),
                defaultMap(request.attributes())
        );
    }

    private DeviceInfo toDeviceInfo(DeviceInfoRequest request) {
        return new DeviceInfo(
                request.deviceId(),
                request.fingerprint(),
                request.ipAddress(),
                request.userAgent(),
                request.platform(),
                request.browser(),
                request.trustedDevice(),
                request.proxyDetected(),
                request.vpnDetected(),
                defaultMap(request.attributes())
        );
    }

    private LocationInfo toLocationInfo(LocationInfoRequest request) {
        return new LocationInfo(
                request.countryCode(),
                request.region(),
                request.city(),
                request.postalCode(),
                request.latitude(),
                request.longitude(),
                request.timezone(),
                request.highRiskCountry()
        );
    }

    private CustomerContext toCustomerContext(CustomerContextRequest request) {
        return new CustomerContext(
                request.customerId(),
                request.accountId(),
                request.segment(),
                request.emailDomain(),
                request.accountAgeDays(),
                request.emailVerified(),
                request.phoneVerified(),
                request.homeCountryCode(),
                request.preferredCurrency(),
                request.knownDeviceIds(),
                defaultMap(request.attributes())
        );
    }

    private Map<String, Object> defaultMap(Map<String, Object> source) {
        return source == null ? Map.of() : source;
    }
}
