package com.osr.openliststrm.openai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.system.service.ISysConfigService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class OpenAIClientTest {

    private MockedStatic<SpringUtils> spring;
    private Request request;
    private JSONObject payload;
    private int status;
    private String responseText;
    private OpenAIApiService service;

    @BeforeEach
    void setUp() {
        status = 200;
        responseText = "answer";
        service = new OpenAIApiService();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            request = chain.request();
            Buffer body = new Buffer();
            assertNotNull(request.body());
            request.body().writeTo(body);
            payload = JSON.parseObject(body.readUtf8());
            String response = JSON.toJSONString(Map.of("choices", new Object[]{
                    Map.of("message", Map.of("content", responseText))
            }));
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("test")
                    .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
        }).build();
        ReflectionTestUtils.setField(service, "http", http);
        spring = mockStatic(SpringUtils.class);
        spring.when(() -> SpringUtils.getBean(OpenAIApiService.class)).thenReturn(service);
    }

    @AfterEach
    void tearDown() {
        spring.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankModelUsesReplacement(String model) throws Exception {
        assertEquals("answer", new OpenAIClient("test-key", null, model).chat("prompt", 1200));

        assertReplacementRequest(1200);
        assertEquals("https://api.openai.com/v1/chat/completions", request.url().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer test-key", request.header("Authorization"));
        assertEquals("application/json; charset=utf-8", request.body().contentType().toString());
        assertEquals("user", payload.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("prompt", payload.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertFalse(payload.containsKey("tools"));
    }

    @Test
    void convenienceConstructorsUseReplacement() throws Exception {
        new OpenAIClient("test-key").chat("prompt", 300);
        assertReplacementRequest(300);
        new OpenAIClient("test-key", "api.openai.com/").chat("prompt", 300);
        assertReplacementRequest(300);
        assertEquals("/v1/chat/completions", request.url().encodedPath());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://api.openai.com", "api.openai.com/", " https://api.openai.com/ ",
            "https://API.OPENAI.COM:443/"})
    void storedLegacyModelMigratesOnlyOnOfficialEndpoint(String endpoint) throws Exception {
        for (String model : new String[]{"gpt-5-mini", " gpt-5-mini-2025-08-07 "}) {
            new OpenAIClient("test-key", endpoint, model).chat("prompt", 300);
            assertReplacementRequest(300);
            assertEquals("/v1/chat/completions", request.url().encodedPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://proxy.example", "https://api.openai.com.proxy.example",
            "http://api.openai.com", "https://api.openai.com:8443", "https://api.openai.com/proxy"})
    void customEndpointKeepsLegacyModel(String endpoint) throws Exception {
        for (String model : new String[]{"gpt-5-mini", "gpt-5-mini-2025-08-07"}) {
            new OpenAIClient("test-key", endpoint, model).chat("prompt", 700);
            assertLegacyRequest(model, 700);
        }
    }

    @ParameterizedTest
    @CsvSource({"https://api.openai.com, custom-model", "https://proxy.example, custom-model",
            "https://api.openai.com, gpt-5-mini-custom"})
    void explicitOtherModelIsPreserved(String endpoint, String model) throws Exception {
        new OpenAIClient("test-key", endpoint, " " + model + " ").chat("prompt", 600);
        assertLegacyRequest(model, 600);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-6.1-terra", "gpt-5.6-terra"})
    void supportedTerraModelsUseCompletionBudget(String model) throws Exception {
        new OpenAIClient("test-key", "https://proxy.example/", model).chat("prompt", 1800);

        assertEquals(model, payload.getString("model"));
        assertEquals("none", payload.getString("reasoning_effort"));
        assertEquals(1800, payload.getIntValue("max_completion_tokens"));
        assertFalse(payload.containsKey("max_tokens"));
        assertEquals(0.0, payload.getDoubleValue("temperature"));
        assertEquals("https://proxy.example/v1/chat/completions", request.url().toString());
    }

    @Test
    void seededConfigurationUsesReplacementThroughChatService() {
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey("openlist.openai.apikey")).thenReturn("test-key");
        when(configs.selectConfigByKey("openlist.openai.endpoint")).thenReturn("https://api.openai.com");
        when(configs.selectConfigByKey("openlist.openai.model")).thenReturn("gpt-5-mini");
        OpenlistConfig config = new OpenlistConfig();
        ReflectionTestUtils.setField(config, "sysConfigService", configs);

        assertEquals("answer", new AiChatService(config).chat("test", "prompt", 1500));
        assertReplacementRequest(1500);
        assertEquals("gpt-5-mini", config.getOpenAiModel());
    }

    @Test
    void metadataEnrichmentStillParsesJsonAndPreservesKnownFields() {
        responseText = "```json\n{\"title\":\"Example\",\"year\":\"2025\",\"season\":\"01\",\"episode\":\"02\"}\n```";
        MediaInfo info = new MediaInfo("example.mkv");
        info.setTitle("Known title");

        assertTrue(new OpenAIClient("test-key", null, "gpt-5-mini").enrich(info, "example.mkv"));

        assertReplacementRequest(300);
        assertEquals("Known title", info.getTitle());
        assertEquals("2025", info.getYear());
        assertEquals("01", info.getSeason());
        assertEquals("02", info.getEpisode());
    }

    @Test
    void requestFailureStillReturnsNull() throws Exception {
        status = 400;
        assertNull(new OpenAIClient("test-key", null, "gpt-6.1-terra").chat("prompt", 300));
        assertReplacementRequest(300);
    }

    @Test
    void blankResponseStillReturnsNull() throws Exception {
        responseText = "  ";
        assertNull(new OpenAIClient("test-key", null, "gpt-6.1-terra").chat("prompt", 300));
    }

    private void assertReplacementRequest(int maxTokens) {
        assertEquals("gpt-6.1-terra", payload.getString("model"));
        assertEquals("none", payload.getString("reasoning_effort"));
        assertEquals(maxTokens, payload.getIntValue("max_completion_tokens"));
        assertFalse(payload.containsKey("max_tokens"));
        assertEquals(0.0, payload.getDoubleValue("temperature"));
    }

    private void assertLegacyRequest(String model, int maxTokens) {
        assertEquals(model, payload.getString("model"));
        assertEquals(maxTokens, payload.getIntValue("max_tokens"));
        assertFalse(payload.containsKey("max_completion_tokens"));
        assertFalse(payload.containsKey("reasoning_effort"));
        assertEquals(0.0, payload.getDoubleValue("temperature"));
    }
}
