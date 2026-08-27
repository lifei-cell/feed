package com.example.feed.api;

import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.FanoutPolicyAuditRepository.FanoutPolicyAuditPage;
import com.example.feed.service.FanoutPolicyAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/fanout-policy-audits")
@PreAuthorize("hasRole('ADMIN')")
public class FanoutPolicyAuditAdminController {
    private final FanoutPolicyAuditService audits;

    public FanoutPolicyAuditAdminController(FanoutPolicyAuditService audits) {
        this.audits = audits;
    }

    @GetMapping
    public FanoutPolicyAuditPage list(
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) FanoutPolicyChangeTrigger triggerType,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return audits.list(authorId, triggerType, beforeId, size);
    }
}
