package com.osr.openliststrm.openai;

import com.osr.common.utils.spring.SpringUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class OpenAIClientEnvironmentTest {

    @Test
    void environmentFallbackAndExplicitModelPrecedence() throws Exception {
        OpenAIApiService service = mock(OpenAIApiService.class);
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            spring.when(() -> SpringUtils.getBean(OpenAIApiService.class)).thenReturn(service);

            new OpenAIClient("test-key", null, " ").chat("fallback", 300);
            verify(service).fetchChatText("test-key", "https://api.openai.com",
                    System.getProperty("expectedOpenAiModel", "gpt-6.1-terra"), "fallback", 300);

            new OpenAIClient("test-key", null, " configured-model ").chat("explicit", 300);
            verify(service).fetchChatText("test-key", "https://api.openai.com", "configured-model", "explicit", 300);
        }
    }
}
