// FILE: main/java/com/synapse/backend/config/LangChain4jConfig.java
package com.synapse.backend.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "langchain4j.enabled", havingValue = "true")
public class LangChain4jConfig {

    @Bean
    @ConditionalOnExpression("'${langchain4j.groq.api-key:}' != ''")
    public ChatLanguageModel chatLanguageModel(@Value("${langchain4j.groq.api-key:}") String apiKey) {

        return OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")

                .apiKey(apiKey)

                .modelName("openai/gpt-oss-20b")

                .temperature(0.0) // 0.0 is best for accurate SQL generation
                .build();
    }
}
