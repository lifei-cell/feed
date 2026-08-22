package com.example.feed.api;

import com.example.feed.repository.KafkaDeadLetterRepository.DeadLetter;
import com.example.feed.security.CurrentUser;
import com.example.feed.service.KafkaDeadLetterAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/kafka-dead-letters")
@PreAuthorize("hasRole('ADMIN')")
public class KafkaDeadLetterAdminController {
    private final KafkaDeadLetterAdminService admin;
    private final CurrentUser currentUser;

    public KafkaDeadLetterAdminController(KafkaDeadLetterAdminService admin, CurrentUser currentUser) {
        this.admin = admin;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<DeadLetter> list(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "20") int size) {
        return admin.list(status, size);
    }

    @GetMapping("/{id}")
    public DeadLetter get(@PathVariable long id) {
        return admin.get(id);
    }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        admin.replay(id, currentUser.id(jwt));
    }

    @PostMapping("/{id}/discard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable long id, @Valid @RequestBody DiscardRequest request,
                        @AuthenticationPrincipal Jwt jwt) {
        admin.discard(id, currentUser.id(jwt), request.note());
    }

    public record DiscardRequest(@Size(max = 255) String note) {
    }
}
