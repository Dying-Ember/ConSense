package com.consense.ai;

import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompetitionQwenConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConsenseProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("CONSENSE_QWEN_ENABLED=true", "CONSENSE_QWEN_API_KEY=synthetic-token");

    @Test void competitionProfileWithoutEndpointRejectsBeforeAnyTransport() {
        runner.withPropertyValues("spring.profiles.active=competition", "CONSENSE_QWEN_BASE_URL=")
                .run(context -> {
                    ConsenseProperties properties = context.getBean(ConsenseProperties.class);
                    assertEquals("", properties.getQwen().getBaseUrl());
                    assertEquals("", properties.getQwenFlash().getBaseUrl());
                    LlmClient local = mock(LlmClient.class), qwen = mock(LlmClient.class);
                    LlmProfiles profiles = new LlmProfiles(properties, local, mock(LlmClient.class), qwen, mock(LlmClient.class));
                    try (LlmProfiles.Scope ignored = profiles.bind(profiles.resolve("qwen"))) {
                        BizException error = assertThrows(BizException.class, () -> new AiGateway(local, profiles).requireAvailable());
                        assertTrue(error.getMessage().contains("missing_configuration"));
                    }
                    verifyNoInteractions(qwen, local);
                });
    }

    @Test void absentEndpointHasNoCloudFallback() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> variables = new HashMap<>();
        variables.put("CONSENSE_QWEN_ENABLED", "true");
        variables.put("CONSENSE_QWEN_API_KEY", "synthetic-token");
        environment.getPropertySources().addFirst(new MapPropertySource("synthetic", variables));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load("competition", new ClassPathResource("application-competition.yml"))) environment.getPropertySources().addLast(source);
        for (PropertySource<?> source : loader.load("base", new ClassPathResource("application.yml"))) environment.getPropertySources().addLast(source);
        ConsenseProperties properties = Binder.get(environment).bind("consense", Bindable.of(ConsenseProperties.class)).get();
        assertEquals("", properties.getQwen().getBaseUrl());
        assertEquals("missing_configuration", new LlmProfiles(properties, mock(LlmClient.class), mock(LlmClient.class))
                .unavailableReason("qwen", properties.getQwen()));
    }

    @Test void explicitEndpointUsesExistingQwenClientAndSingleVersionSegment() {
        runner.withPropertyValues("spring.profiles.active=competition", "CONSENSE_QWEN_BASE_URL=http://127.0.0.1:18081")
                .run(context -> {
                    ConsenseProperties.Llm config = context.getBean(ConsenseProperties.class).getQwen();
                    assertEquals("http://127.0.0.1:18081", config.getBaseUrl());
                    HttpSupport http = mock(HttpSupport.class);
                    when(http.get(anyString(), anyLong(), anyString())).thenReturn("{\"data\":[]}");
                    when(http.postJsonWithoutReplay(anyString(), anyString(), anyLong(), anyString())).thenReturn(
                            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}");
                    QwenChatClient client = new QwenChatClient(config, http);
                    assertTrue(client.available());
                    assertEquals("ok", client.chat(Collections.singletonList(LlmClient.ChatTurn.user("synthetic"))));
                    verify(http).get("http://127.0.0.1:18081/v1/models", 4000, "Bearer synthetic-token");
                    verify(http).postJsonWithoutReplay(eq("http://127.0.0.1:18081/v1/chat/completions"), anyString(), anyLong(), eq("Bearer synthetic-token"));
                });
    }

    @Test void defaultProfileAndRehearsalOverrideRemainUnchanged() throws Exception {
        MockEnvironment defaultEnvironment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))) {
            defaultEnvironment.getPropertySources().addLast(source);
        }
        assertEquals("https://maas.qianwenaiapi.com/compatible-mode",
                Binder.get(defaultEnvironment).bind("consense", Bindable.of(ConsenseProperties.class)).get().getQwen().getBaseUrl());
        runner.withPropertyValues("spring.profiles.active=competition", "consense.qwen.base-url=http://127.0.0.1:28681")
                .run(context -> assertEquals("http://127.0.0.1:28681", context.getBean(ConsenseProperties.class).getQwen().getBaseUrl()));
    }
}
