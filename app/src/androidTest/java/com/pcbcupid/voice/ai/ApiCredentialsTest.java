package com.pcbcupid.voice.ai;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApiCredentialsTest {
    @Test public void encryptedKeysAreProviderScopedAndRemovable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "credentials-test-" + UUID.randomUUID();
        try {
            ApiCredentials store = new ApiCredentials(context, name);
            store.save(AiProvider.DEEPSEEK, "fake-deepseek-test-secret", "deepseek-flash");
            assertEquals("fake-deepseek-test-secret", store.key(AiProvider.DEEPSEEK));
            assertFalse(store.hasKey(AiProvider.OPENAI));
            assertFalse(context.getSharedPreferences(name, 0).getAll().toString().contains("fake-deepseek-test-secret"));
            store.save(AiProvider.DEEPSEEK, "", "deepseek-flash");
            assertEquals("fake-deepseek-test-secret", store.key(AiProvider.DEEPSEEK));
            store.save(AiProvider.OPENAI, "fake-openai-test-secret", "gpt-4.1-mini");
            assertEquals(AiProvider.OPENAI, store.provider());
            assertEquals("fake-deepseek-test-secret", store.key(AiProvider.DEEPSEEK));
            store.remove(AiProvider.DEEPSEEK);
            assertFalse(store.hasKey(AiProvider.DEEPSEEK));
            assertTrue(store.hasKey(AiProvider.OPENAI));
            assertThrows(IllegalArgumentException.class, () -> store.save(AiProvider.DEEPSEEK, "", "deepseek-flash"));
        } finally { context.deleteSharedPreferences(name); }
    }
}
