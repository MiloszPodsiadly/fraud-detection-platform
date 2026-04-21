package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.AlertDetailsResponse;
import com.frauddetection.alert.api.AlertSummaryResponse;
import com.frauddetection.alert.api.CustomerContextResponse;
import com.frauddetection.alert.api.DeviceInfoResponse;
import com.frauddetection.alert.api.LocationInfoResponse;
import com.frauddetection.alert.api.MerchantInfoResponse;
import com.frauddetection.alert.api.MoneyResponse;
import com.frauddetection.alert.domain.AlertCase;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import org.springframework.stereotype.Component;

@Component
public class AlertResponseMapper {

    public AlertSummaryResponse toSummary(AlertCase alertCase) {
        return new AlertSummaryResponse(
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.riskLevel(),
                alertCase.fraudScore(),
                alertCase.alertStatus(),
                alertCase.alertReason(),
                alertCase.alertTimestamp()
        );
    }

    public AlertDetailsResponse toDetails(AlertCase alertCase) {
        return new AlertDetailsResponse(
                alertCase.alertId(),
                alertCase.transactionId(),
                alertCase.customerId(),
                alertCase.correlationId(),
                alertCase.createdAt(),
                alertCase.alertTimestamp(),
                alertCase.riskLevel(),
                alertCase.fraudScore(),
                alertCase.alertStatus(),
                alertCase.alertReason(),
                alertCase.reasonCodes(),
                toMoneyResponse(alertCase.transactionAmount()),
                toMerchantInfoResponse(alertCase.merchantInfo()),
                toDeviceInfoResponse(alertCase.deviceInfo()),
                toLocationInfoResponse(alertCase.locationInfo()),
                toCustomerContextResponse(alertCase.customerContext()),
                alertCase.scoreDetails(),
                alertCase.featureSnapshot(),
                alertCase.analystDecision(),
                alertCase.analystId(),
                alertCase.decisionReason(),
                alertCase.decisionTags(),
                alertCase.decidedAt()
        );
    }

    public MoneyResponse toMoneyResponse(Money money) {
        if (money == null) {
            return null;
        }
        return new MoneyResponse(money.amount(), money.currency());
    }

    public MerchantInfoResponse toMerchantInfoResponse(MerchantInfo merchantInfo) {
        if (merchantInfo == null) {
            return null;
        }
        return new MerchantInfoResponse(
                merchantInfo.merchantId(),
                merchantInfo.merchantName(),
                merchantInfo.merchantCategoryCode(),
                merchantInfo.merchantCategory(),
                merchantInfo.acquiringCountryCode(),
                merchantInfo.channel(),
                merchantInfo.cardPresent(),
                merchantInfo.attributes()
        );
    }

    public DeviceInfoResponse toDeviceInfoResponse(DeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return null;
        }
        return new DeviceInfoResponse(
                deviceInfo.deviceId(),
                deviceInfo.fingerprint(),
                deviceInfo.ipAddress(),
                deviceInfo.userAgent(),
                deviceInfo.platform(),
                deviceInfo.browser(),
                deviceInfo.trustedDevice(),
                deviceInfo.proxyDetected(),
                deviceInfo.vpnDetected(),
                deviceInfo.attributes()
        );
    }

    public LocationInfoResponse toLocationInfoResponse(LocationInfo locationInfo) {
        if (locationInfo == null) {
            return null;
        }
        return new LocationInfoResponse(
                locationInfo.countryCode(),
                locationInfo.region(),
                locationInfo.city(),
                locationInfo.postalCode(),
                locationInfo.latitude(),
                locationInfo.longitude(),
                locationInfo.timezone(),
                locationInfo.highRiskCountry()
        );
    }

    public CustomerContextResponse toCustomerContextResponse(CustomerContext customerContext) {
        if (customerContext == null) {
            return null;
        }
        return new CustomerContextResponse(
                customerContext.customerId(),
                customerContext.accountId(),
                customerContext.segment(),
                customerContext.emailDomain(),
                customerContext.accountAgeDays(),
                customerContext.emailVerified(),
                customerContext.phoneVerified(),
                customerContext.homeCountryCode(),
                customerContext.preferredCurrency(),
                customerContext.knownDeviceIds(),
                customerContext.attributes()
        );
    }
}
