package com.example.feed.service;

import com.example.feed.domain.FanoutPolicyChangeTrigger;
import com.example.feed.repository.FanoutPolicyAuditRepository;
import com.example.feed.repository.FanoutPolicyAuditRepository.FanoutPolicyAuditPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FanoutPolicyAuditService {
    private final FanoutPolicyAuditRepository audits;

    public FanoutPolicyAuditService(FanoutPolicyAuditRepository audits) {
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public FanoutPolicyAuditPage list(Long authorId, FanoutPolicyChangeTrigger triggerType,
                                      Long beforeId, Integer size) {
        int limit = size == null ? 20 : Math.max(1, Math.min(size, 100));
        return audits.findPage(authorId, triggerType, beforeId, limit);
    }
}
