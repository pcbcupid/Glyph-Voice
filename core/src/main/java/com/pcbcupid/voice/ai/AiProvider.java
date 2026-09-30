package com.pcbcupid.voice.ai;

/** Fixed HTTPS destinations: a user key can never be sent to a custom host. */
public enum AiProvider {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1/responses", "gpt-4.1-mini");
    public final String label, endpoint, defaultModel;
    AiProvider(String label, String endpoint, String defaultModel) {
        this.label = label; this.endpoint = endpoint; this.defaultModel = defaultModel;
    }
    @Override public String toString() { return label; }
}
