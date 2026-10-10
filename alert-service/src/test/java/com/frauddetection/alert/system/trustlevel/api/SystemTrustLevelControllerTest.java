package com.frauddetection.alert.system.trustlevel.api;

import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.system.trustlevel.application.SystemTrustLevelService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemTrustLevelControllerTest {

    @Test
    void delegatesResponseAssemblyAndAuditsTheSensitiveRead() {
        SystemTrustLevelService service = mock(SystemTrustLevelService.class);
        SensitiveReadAuditService auditService = mock(SensitiveReadAuditService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        SystemTrustLevelResponse response = mock(SystemTrustLevelResponse.class);
        when(service.trustLevel()).thenReturn(response);
        SystemTrustLevelController controller = new SystemTrustLevelController(service, auditService);

        assertThat(controller.trustLevel(request)).isSameAs(response);

        verify(service).trustLevel();
        verify(auditService).audit(
                ReadAccessEndpointCategory.SYSTEM_TRUST_LEVEL,
                ReadAccessResourceType.SYSTEM_TRUST_LEVEL,
                null,
                1,
                request
        );
    }
}
