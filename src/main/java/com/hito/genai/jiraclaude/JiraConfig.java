package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.JiraClaudeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JiraClaudeProperties.class)
public class JiraConfig {
}
