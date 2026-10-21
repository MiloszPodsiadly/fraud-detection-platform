package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Only explicitly configured trusted IdP groups grant policy authorities. */
@ConfigurationProperties(prefix = "app.security.model-policy")
public record ModelPolicyIamMapping(Map<String, Set<String>> groupMapping) {
    public ModelPolicyIamMapping {
        groupMapping = groupMapping == null ? Map.of() : groupMapping.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
        groupMapping.values().forEach(grants -> {
            if (grants == null || !ModelPolicyAuthority.ALL.containsAll(grants)) {
                throw new IllegalArgumentException("Unknown model policy IAM authority");
            }
        });
    }

    public Set<String> authorities(Set<String> groups) {
        return groups.stream().flatMap(group -> groupMapping.getOrDefault(group, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
