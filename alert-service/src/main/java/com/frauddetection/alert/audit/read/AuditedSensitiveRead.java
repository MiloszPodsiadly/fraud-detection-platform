package com.frauddetection.alert.audit.read;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker-only annotation for endpoints that require sensitive-read audit.
 *
 * <p>The annotation does not execute auditing by itself. Architecture tests enforce
 * that annotated endpoints also call {@link SensitiveReadAuditService} explicitly.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedSensitiveRead {
    String action() default "READ";
}
