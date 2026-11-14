package com.hito.genai.jiraclaude;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JiraClaudeProperties.class, RepositoryQualityProperties.class})
public class JiraConfig {
}
