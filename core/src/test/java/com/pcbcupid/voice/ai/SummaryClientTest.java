package com.pcbcupid.voice.ai;

import java.io.IOException;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SummaryClientTest {
    private static final String SUCCESS = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"A useful summary.\"}}]}";
    private static SummaryClient client(MockWebServer server) {
        return new SummaryClient(new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().url(server.url("/summary")).build())).build());
    }
    @Test public void providersUseFixedHttpsEndpoints() {
        for (AiProvider p : AiProvider.values()) assertTrue(p.endpoint.startsWith("https://"));
        assertEquals("api.deepseek.com", HttpUrl.get(AiProvider.DEEPSEEK.endpoint).host());
        assertEquals("api.openai.com", HttpUrl.get(AiProvider.OPENAI.endpoint).host());
    }
    @Test public void openAiPayloadDoesNotStoreOrAttachAudio() throws Exception {
        JSONObject p = SummaryClient.payload(AiProvider.OPENAI, "gpt-4.1-mini", "Only this selected text");
        assertFalse(p.getBoolean("store"));
        assertEquals("Only this selected text", p.getString("input"));
        assertFalse(p.has("audio"));
        assertFalse(p.has("tools"));
        assertTrue(p.getString("instructions").contains("Always write the entire response in English"));
        assertFalse(p.getString("instructions").contains("in the transcript's language"));
    }
    @Test public void bothProvidersGetEnglishTranslationInstructions() throws Exception {
        JSONObject deepSeek = SummaryClient.payload(AiProvider.DEEPSEEK, "deepseek-flash", "你好");
        JSONObject system = deepSeek.getJSONArray("messages").getJSONObject(0);
        assertEquals("system", system.getString("role"));
        assertEquals(SummaryClient.INSTRUCTIONS, system.getString("content"));
        assertTrue(system.getString("content").contains("Translate non-English or mixed-language content"));
        assertEquals(SummaryClient.INSTRUCTIONS,
                SummaryClient.payload(AiProvider.OPENAI, "gpt-4.1-mini", "你好").getString("instructions"));
    }
    @Test public void requestsNaturalParagraphsInsteadOfTemplateSections() throws Exception {
        for (AiProvider provider : AiProvider.values()) {
            JSONObject body = SummaryClient.payload(provider, provider.defaultModel, "A short thought.");
            String instructions = provider == AiProvider.OPENAI ? body.getString("instructions")
                    : body.getJSONArray("messages").getJSONObject(0).getString("content");
            assertTrue(instructions.contains("short, flowing paragraphs"));
            assertTrue(instructions.contains("Do not use headings, section labels, bullet points, numbered lists"));
            assertFalse(instructions.contains("Use a short overview and key points"));
            assertTrue(instructions.contains("Always write the entire response in English"));
        }
        assertEquals("natural-english-summary-v3", SummaryClient.PROMPT_VERSION);
    }
    @Test public void chineseOutputIsRejectedRatherThanSavedAsSuccess() throws Exception {
        IOException error = assertThrows(IOException.class, () -> SummaryClient.result(AiProvider.DEEPSEEK,
                new JSONObject(SUCCESS.replace("A useful summary.", "这是摘要"))));
        assertTrue(error.getMessage().contains("non-English script"));
        assertEquals("José will attend.", SummaryClient.result(AiProvider.DEEPSEEK,
                new JSONObject(SUCCESS.replace("A useful summary.", "José will attend."))));
    }
    @Test public void parsesOpenAiTextAfterNonMessageOutput() throws Exception {
        JSONObject p = new JSONObject("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Summary\"}]}]}");
        assertEquals("Summary", SummaryClient.result(AiProvider.OPENAI, p));
        p.put("status", "incomplete");
        assertThrows(IOException.class, () -> SummaryClient.result(AiProvider.OPENAI, p));
    }
    @Test public void refusesTruncatedEmptyOrNullAnswers() throws Exception {
        for (String answer : new String[]{SUCCESS.replace("stop", "length"), SUCCESS.replace("A useful summary.", ""),
                SUCCESS.replace("\"A useful summary.\"", "null")}) {
            assertThrows(IOException.class, () -> SummaryClient.result(AiProvider.DEEPSEEK, new JSONObject(answer)));
        }
    }
    @Test public void sendsOnlySnapshotAndReturnsSummary() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(SUCCESS)); server.start();
            try (SummaryClient client = client(server)) {
                assertEquals("A useful summary.", client.summarize(AiProvider.DEEPSEEK, "fake-test-key", "deepseek-flash", "Selected words"));
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("Bearer fake-test-key", request.getHeader("Authorization"));
                JSONObject body = new JSONObject(request.getBody().readUtf8());
                assertEquals("Selected words", body.getJSONArray("messages").getJSONObject(1).getString("content"));
                assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
                assertFalse(body.toString().contains("fake-test-key"));
            }
        }
    }
    @Test public void errorsNeverEchoProviderBodiesAndDoNotRetryOrRedirect() throws Exception {
        for (int status : new int[]{401, 402, 429, 500, 307}) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setResponseCode(status).setHeader("Location", "https://example.com/")
                        .setBody("sensitive echoed transcript fake-test-key")); server.start();
                try (SummaryClient client = client(server)) {
                    IOException error = assertThrows(IOException.class, () -> client.summarize(AiProvider.DEEPSEEK, "fake-test-key", "deepseek-flash", "text"));
                    assertFalse(error.getMessage().contains("fake-test-key"));
                    assertFalse(error.getMessage().contains("sensitive"));
                    assertEquals(1, server.getRequestCount());
                }
            }
        }
    }
    @Test public void malformedResponseGetsFriendlyError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{broken")); server.start();
            try (SummaryClient client = client(server)) {
                assertTrue(assertThrows(IOException.class, () -> client.summarize(AiProvider.DEEPSEEK, "fake-test-key", "deepseek-flash", "text"))
                        .getMessage().contains("unreadable"));
            }
        }
    }
    @Test public void oversizedInputIsNotSent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            try (SummaryClient client = client(server)) {
                assertThrows(IOException.class, () -> client.summarize(AiProvider.DEEPSEEK, "fake-test-key", "deepseek-flash", "x".repeat(SummaryClient.MAX_TEXT_CHARS + 1)));
                assertEquals(0, server.getRequestCount());
            }
        }
    }
    @Test public void cancellationInterruptsWaitingHttpCall() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            try (SummaryClient client = client(server)) {
                Future<String> call = worker.submit(() -> client.summarize(AiProvider.DEEPSEEK, "fake-test-key", "deepseek-flash", "text"));
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                client.cancel();
                ExecutionException error = assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
                assertTrue(error.getCause().getMessage().contains("cancelled"));
            }
        } finally { worker.shutdownNow(); }
    }
}
