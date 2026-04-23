package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

@Component
public class AnalystAuthenticationFactory {

    public Authentication authenticated(AnalystPrincipal principal) {
        if (principal.userId().isBlank()) {
            throw new BadCredentialsException("Analyst user id is required.");
        }

        var grantedAuthorities = Stream.concat(
                        principal.authorities().stream(),
                        principal.roles().stream().map(role -> "ROLE_" + role.name())
                )
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, grantedAuthorities);
    }
}
