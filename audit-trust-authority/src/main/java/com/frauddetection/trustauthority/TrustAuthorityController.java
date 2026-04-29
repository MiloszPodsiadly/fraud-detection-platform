package com.frauddetection.trustauthority;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trust")
public class TrustAuthorityController {

    private final TrustAuthorityService service;
    private final TrustAuthorityProperties properties;

    public TrustAuthorityController(TrustAuthorityService service, TrustAuthorityProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/sign")
    public TrustSignResponse sign(
            @RequestHeader(name = "X-Internal-Trust-Token", required = false) String token,
            @Valid @RequestBody TrustSignRequest request
    ) {
        requireInternalToken(token);
        return service.sign(request);
    }

    @GetMapping("/keys")
    public List<TrustKeyResponse> keys() {
        return service.keys();
    }

    @PostMapping("/verify")
    public TrustVerifyResponse verify(
            @RequestHeader(name = "X-Internal-Trust-Token", required = false) String token,
            @Valid @RequestBody TrustVerifyRequest request
    ) {
        requireInternalToken(token);
        return service.verify(request);
    }

    private void requireInternalToken(String token) {
        if (!StringUtils.hasText(token) || !token.equals(properties.getInternalToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal trust authority credentials are invalid.");
        }
    }
}
