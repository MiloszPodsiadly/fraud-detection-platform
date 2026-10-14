package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/model-card")
public class ModelCardController {

    private final ModelCardReadService readService;

    public ModelCardController(ModelCardReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/current")
    @AuditedSensitiveRead
    public ModelCardResponse currentCard() {
        return readService.currentCard();
    }
}
