package com.frauddetection.alert.audit.read;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Executable read-audit policy for a sensitive GET endpoint. The response advice
 * and failure interceptor resolve this metadata from the mapped Spring handler.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedSensitiveRead {
    ReadAccessEndpointCategory category() default ReadAccessEndpointCategory.UNCLASSIFIED;

    ReadAccessResourceType resourceType() default ReadAccessResourceType.UNKNOWN;

    String resourceIdVariable() default "";

    String resourceIdParameter() default "";
}
