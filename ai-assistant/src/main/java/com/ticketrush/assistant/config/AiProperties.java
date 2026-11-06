package com.ticketrush.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spec §24.6 model settings and §24.3/§24.4 retrieval settings ({@code ticketrush.ai.*}). */
@ConfigurationProperties("ticketrush.ai")
public record AiProperties(String model, int maxTokens, String effort, int topK, double similarityThreshold,
                           String kbVersion, boolean ingestOnStartup) {}
