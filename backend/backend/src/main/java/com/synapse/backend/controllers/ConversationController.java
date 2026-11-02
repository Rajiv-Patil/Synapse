// FILE: main/java/com/synapse/backend/controllers/ConversationController.java
package com.synapse.backend.controllers;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.ChatResponse;
import com.synapse.backend.models.ChatRequest;
import com.synapse.backend.models.Conversation;
import com.synapse.backend.services.ChatProcessingService;
import com.synapse.backend.services.ConversationService;
import com.synapse.backend.services.CsvStorageService;
import com.synapse.backend.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatProcessingService chatProcessingService;
    private final UserService userService;
    private final CsvStorageService csvStorageService;

    public ConversationController(ConversationService conversationService,
                                  ChatProcessingService chatProcessingService,
                                  UserService userService,
                                  CsvStorageService csvStorageService) {
        this.conversationService = conversationService;
        this.chatProcessingService = chatProcessingService;
        this.userService = userService;
        this.csvStorageService = csvStorageService;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> getConversations(Authentication authentication) {
        String userSub = extractUserSub(authentication);
        List<Conversation> list = conversationService.getConversationsByUser(userSub);
        return ResponseEntity.ok(list);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(@RequestBody(required = false) Map<String, String> body,
                                                           Authentication authentication) {
        String userSub = extractUserSub(authentication);
        String userEmail = extractUserEmail(authentication);
        String userName = extractUserName(authentication);

        String title = body != null ? body.get("title") : "New Analysis";
        String documentId = body != null ? body.get("documentId") : null;
        String documentName = null;

        if ((documentId != null && !documentId.isBlank()) ||
                (body != null && body.get("documentName") != null && !body.get("documentName").isBlank())) {
            return ResponseEntity.badRequest().build();
        }

        Conversation conv = conversationService.createConversation(title, userSub, userEmail, userName, documentId, documentName);
        return ResponseEntity.ok(conv);
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> getConversation(@PathVariable("id") String id, Authentication authentication) {
        String userSub = extractUserSub(authentication);
        var userOpt = userService.findBySub(userSub);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "User not found"));
        }

        Optional<Conversation> opt = conversationService.getByIdAndUser(id, userOpt.get().getId());
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(opt.get());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<?> deleteConversation(@PathVariable("id") String id, Authentication authentication) {
        String userSub = extractUserSub(authentication);
        var userOpt = userService.findBySub(userSub);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "User not found"));
        }

        boolean deleted = conversationService.delete(id, userOpt.get().getId());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> addConversationMessage(@PathVariable("id") String id,
                                                    @RequestBody Map<String, String> body,
                                                    Authentication authentication) {
        String userSub = extractUserSub(authentication);
        var userOpt = userService.findBySub(userSub);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "User not found"));
        }
        UUID userId = userOpt.get().getId();
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

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, Authentication authentication) {
        String prompt = request.getPrompt();
        String convId = request.getConversationId();
        String docId = request.getDocumentId();

        String userSub = extractUserSub(authentication);
        String userEmail = extractUserEmail(authentication);
        String userName = extractUserName(authentication);

        var userOpt = userService.findBySub(userSub);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "User not found"));
        }
        UUID userId = userOpt.get().getId();

        // Auto-provision conversation and schema if it does not exist in DB
        Optional<Conversation> existingConv = Optional.empty();
        if (convId != null && !convId.isBlank()) {
            existingConv = conversationService.getByIdAndUser(convId, userId);
            if (existingConv.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
        } else {
            Conversation created = conversationService.createConversation(
                    "New Analysis", userSub, userEmail, userName, null, null
            );
            convId = created.getId();
        }

        if (docId == null || docId.isBlank()) {
            docId = existingConv.map(Conversation::getDocumentId).orElse(null);
        }
        if (docId != null && !docId.isBlank() && !csvStorageService.belongsToConversation(docId, convId)) {
            return ResponseEntity.notFound().build();
        }

        // Record User Message
        ChatMessage userMessage = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(prompt)
                .timestamp(Instant.now())
                .build();
        conversationService.addMessage(convId, userId, userMessage);

        // Process with ChatProcessingService
        ChatResponse response = chatProcessingService.processStructuredQuery(prompt, convId, docId);

        // Record Assistant Message
        ChatMessage assistantMessage = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(response.getContent())
                .sqlResult(response.getSqlResult())
                .timestamp(Instant.now())
                .build();
        Conversation updated = conversationService.addMessage(convId, userId, assistantMessage);

        Map<String, Object> resp = new HashMap<>();
        resp.put("conversationId", convId);
        resp.put("message", assistantMessage);
        resp.put("conversation", updated);

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(value = "message", defaultValue = "Hello") String message,
                                     @RequestParam(value = "documentId", required = false) String documentId,
                                     @RequestParam(value = "conversationId", required = false) String conversationId,
                                     Authentication authentication) {
        var userOpt = userService.findBySub(extractUserSub(authentication));
        if (userOpt.isEmpty() || conversationId == null ||
                conversationService.getByIdAndUser(conversationId, userOpt.get().getId()).isEmpty() ||
                (documentId != null && !documentId.isBlank() &&
                        !csvStorageService.belongsToConversation(documentId, conversationId))) {
            return ResponseEntity.notFound().build();
        }
        String result = chatProcessingService.processQuery(message, conversationId, documentId);
        return ResponseEntity.ok(Map.of(
                "reply", result,
                "message", result
        ));
    }

    private String extractUserSub(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            return oidcUser.getSubject();
        }
        return authentication != null ? authentication.getName() : "guest";
    }

    private String extractUserEmail(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            return oidcUser.getEmail();
        }
        return null;
    }

    private String extractUserName(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof DefaultOidcUser oidcUser) {
            Object name = oidcUser.getAttributes().get("name");
            if (name instanceof String value && !value.isBlank()) return value;
            String email = oidcUser.getEmail();
            return email != null && !email.isBlank() ? email : oidcUser.getSubject();
        }
        return null;
    }
}
