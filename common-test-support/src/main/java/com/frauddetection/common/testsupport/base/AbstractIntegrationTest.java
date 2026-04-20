package com.frauddetection.common.testsupport.base;

import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    @BeforeAll
    void startContainers() {
        FraudPlatformContainers.startAll();
    }
}
