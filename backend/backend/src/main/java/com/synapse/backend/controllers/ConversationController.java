package com.synapse.backend.controllers;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.ChatRequest;
import com.synapse.backend.models.Conversation;
import com.synapse.backend.services.ChatProcessingService;
import com.synapse.backend.services.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatProcessingService chatProcessingService;

    public ConversationController(ConversationService conversationService,
                                  ChatProcessingService chatProcessingService) {
        this.conversationService = conversationService;
        this.chatProcessingService = chatProcessingService;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> getConversations(Authentication authentication) {
        String userId = authentication != null ? authentication.getName() : "guest";
        List<Conversation> list = conversationService.getConversationsByUser(userId);
        return ResponseEntity.ok(list);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(@RequestBody(required = false) Map<String, String> body,
                                                           Authentication authentication) {
        String userId = authentication != null ? authentication.getName() : "guest";
        String title = body != null ? body.get("title") : "New Analysis";
        String documentId = body != null ? body.get("documentId") : null;
        String documentName = body != null ? body.get("documentName") : null;

        Conversation conv = conversationService.createConversation(title, userId, documentId, documentName);
        return ResponseEntity.ok(conv);
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> getConversation(@PathVariable String id) {
        Optional<Conversation> opt = conversationService.getById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(opt.get());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<?> deleteConversation(@PathVariable String id) {
        boolean deleted = conversationService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        String prompt = request.getPrompt();
        String convId = request.getConversationId();
        String docId = request.getDocumentId();

        if (convId == null || convId.isBlank()) {
            convId = UUID.randomUUID().toString();
        }

        // Record User Message
        ChatMessage userMessage = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(prompt)
                .timestamp(Instant.now())
                .build();
        conversationService.addMessage(convId, userMessage);

        // Process with ChatProcessingService
        String responseContent = chatProcessingService.processQuery(prompt, docId);

        // Record Assistant Message
        ChatMessage assistantMessage = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(responseContent)
                .timestamp(Instant.now())
                .build();
        Conversation updated = conversationService.addMessage(convId, assistantMessage);

        Map<String, Object> resp = new HashMap<>();
        resp.put("conversationId", convId);
        resp.put("message", assistantMessage);
        resp.put("conversation", updated);

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(value = "message", defaultValue = "Hello") String message,
                                     @RequestParam(value = "documentId", required = false) String documentId,
                                     @RequestParam(value = "conversationId", required = false) String conversationId) {
        String result = chatProcessingService.processQuery(message, documentId);
        return ResponseEntity.ok(Map.of(
                "reply", result,
                "message", result
        ));
    }
}
