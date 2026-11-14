package com.hito.genai.jiraclaude;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Explicit registration for repository quality configuration properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryQualityProperties.class)
public class RepositoryQualityConfig {
}
