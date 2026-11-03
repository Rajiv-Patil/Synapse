package com.synapse.backend.controllers;

import com.synapse.backend.models.ChatMessage;
import com.synapse.backend.models.ChatRequest;
import com.synapse.backend.models.ChatResponse;
import com.synapse.backend.models.Conversation;
import com.synapse.backend.services.ChatProcessingService;
import com.synapse.backend.services.ConversationClient;
import com.synapse.backend.services.DatasetClient;
import com.synapse.backend.services.RequestIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@Profile({"ai", "legacy"})
@RequestMapping("/api")
public class ChatController {
    private final ConversationClient conversationClient;
    private final ChatProcessingService chatProcessingService;
    private final DatasetClient datasetClient;
    private final RequestIdentity requestIdentity;

    public ChatController(ConversationClient conversationClient,
                          ChatProcessingService chatProcessingService,
                          DatasetClient datasetClient,
                          RequestIdentity requestIdentity) {
        this.conversationClient = conversationClient;
        this.chatProcessingService = chatProcessingService;
        this.datasetClient = datasetClient;
        this.requestIdentity = requestIdentity;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request, Authentication authentication,
            @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
            @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        String conversationId = request.getConversationId();
        Optional<Conversation> existing = Optional.empty();
        if (conversationId != null && !conversationId.isBlank()) {
            existing = conversationClient.getConversation(conversationId, identity);
            if (existing.isEmpty()) return ResponseEntity.notFound().build();
        } else {
            Conversation created = conversationClient.createConversation("New Analysis", identity);
            conversationId = created.getId();
        }

        String documentId = request.getDocumentId();
        if (documentId == null || documentId.isBlank()) documentId = existing.map(Conversation::getDocumentId).orElse(null);
        if (documentId != null && !documentId.isBlank() &&
                datasetClient.getDocument(documentId, conversationId, identity).isEmpty()) return ResponseEntity.notFound().build();

        ChatMessage userMessage = ChatMessage.builder().id(UUID.randomUUID().toString()).role("user")
                .content(request.getPrompt()).timestamp(Instant.now()).build();
        conversationClient.addMessage(conversationId, userMessage, identity);

        ChatResponse result = chatProcessingService.processStructuredQuery(request.getPrompt(), conversationId, documentId, identity);
        ChatMessage assistantMessage = ChatMessage.builder().id(UUID.randomUUID().toString()).role("assistant")
                .content(result.getContent()).sqlResult(result.getSqlResult()).timestamp(Instant.now()).build();
        Conversation updated = conversationClient.addMessage(conversationId, assistantMessage, identity);
        return ResponseEntity.ok(Map.of("conversationId", conversationId,
                "message", assistantMessage, "conversation", updated));
    }

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(value = "message", defaultValue = "Hello") String message,
                                     @RequestParam(value = "documentId", required = false) String documentId,
                                     @RequestParam(value = "conversationId", required = false) String conversationId,
                                     Authentication authentication,
                                     @RequestHeader(value = "X-Authenticated-Sub", required = false) String forwardedSub,
                                     @RequestHeader(value = "X-Authenticated-User-Id", required = false) String forwardedUserId) {
        var identity = requestIdentity.resolve(authentication, forwardedSub, forwardedUserId);
        if (conversationId == null || conversationClient.getConversation(conversationId, identity).isEmpty() ||
                (documentId != null && !documentId.isBlank() &&
                        datasetClient.getDocument(documentId, conversationId, identity).isEmpty())) {
            return ResponseEntity.notFound().build();
        }
        String reply = chatProcessingService.processQuery(message, conversationId, documentId, identity);
        return ResponseEntity.ok(Map.of("reply", reply, "message", reply));
    }
}
