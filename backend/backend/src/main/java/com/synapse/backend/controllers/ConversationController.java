package com.synapse.backend.controllers;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.Conversation;
import com.synapse.backend.services.ConversationService;
import com.synapse.backend.services.RequestIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;

import java.time.Instant;
import java.util.*;

@RestController
@Profile({"conversation", "legacy"})
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversationService;
    private final RequestIdentity requestIdentity;

    public ConversationController(ConversationService conversationService, RequestIdentity requestIdentity) {
        this.conversationService = conversationService;
        this.requestIdentity = requestIdentity;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> getConversations(Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        List<Conversation> list = conversationService.getConversationsByUser(identity.userId());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(@RequestBody(required = false) Map<String, String> body,
                                                           Authentication authentication,
                                                           @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
                                                           @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);

        String title = body != null ? body.get("title") : "New Analysis";
        String documentId = body != null ? body.get("documentId") : null;
        String documentName = null;

        if ((documentId != null && !documentId.isBlank()) ||
                (body != null && body.get("documentName") != null && !body.get("documentName").isBlank())) {
            return ResponseEntity.badRequest().build();
        }

        Conversation conv = conversationService.createConversation(title, identity.userId(), identity.subject(),
                identity.email(), identity.name(), documentId, documentName);
        return ResponseEntity.ok(conv);
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> getConversation(@PathVariable("id") String id, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        Optional<Conversation> opt = conversationService.getByIdAndUser(id, identity.userId());
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(opt.get());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<?> deleteConversation(@PathVariable("id") String id, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        boolean deleted = conversationService.delete(id, identity.userId());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> addConversationMessage(@PathVariable("id") String id,
                                                    @RequestBody Map<String, String> body,
                                                    Authentication authentication,
                                                    @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
                                                    @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        UUID userId = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId).userId();
        if (conversationService.getByIdAndUser(id, userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message content is required"));
        }
        ChatMessage message = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(content)
                .timestamp(Instant.now())
                .build();
        Conversation updated = conversationService.addMessage(id, userId, message);
        return ResponseEntity.ok(Map.of("message", message, "conversation", updated));
    }

    @GetMapping("/internal/conversations/{id}")
    public ResponseEntity<?> getConversationInternal(@PathVariable String id, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        return conversationService.getByIdAndUser(id, identity.userId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/internal/conversations")
    public ResponseEntity<Conversation> createConversationInternal(@RequestBody(required = false) Map<String, String> body,
            Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        String title = body == null ? "New Analysis" : body.getOrDefault("title", "New Analysis");
        return ResponseEntity.ok(conversationService.createConversation(title, identity.userId(), identity.subject(),
                identity.email(), identity.name(), null, null));
    }

    @PostMapping("/internal/conversations/{id}/messages")
    public ResponseEntity<?> addMessageInternal(@PathVariable String id, @RequestBody ChatMessage message,
            Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        try {
            return ResponseEntity.ok(conversationService.addMessage(id, identity.userId(), message));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/internal/conversations/{id}/document")
    public ResponseEntity<?> attachDocumentInternal(@PathVariable String id, @RequestBody Map<String, String> body,
            Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        boolean attached = conversationService.attachDocument(id, identity.userId(),
                body.get("documentId"), body.get("documentName"));
        return attached ? ResponseEntity.ok(Map.of("attached", true)) : ResponseEntity.notFound().build();
    }
}
