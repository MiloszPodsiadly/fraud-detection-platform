package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.DemoAuthFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

class DemoAuthSecurityConfigurer {

    void configure(HttpSecurity http, ObjectProvider<DemoAuthFilter> demoAuthFilter) {
        // Local/dev auth path: demo headers remain an explicit opt-in adapter.
        demoAuthFilter.ifAvailable(filter -> http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
    }
}
