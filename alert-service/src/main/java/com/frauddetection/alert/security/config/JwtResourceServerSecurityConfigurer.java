package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.JwtAnalystAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtResourceServerSecurityConfigurer {

    void configure(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<JwtAnalystAuthenticationConverter> jwtAnalystAuthenticationConverter
    ) throws Exception {
        if (jwtDecoder.getIfAvailable() == null) {
            return;
        }
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                .jwtAuthenticationConverter(token -> jwtAnalystAuthenticationConverter
                        .getObject()
                        .convert(token))
        ));
    }
}
