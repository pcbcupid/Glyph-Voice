package com.pcbcupid.voice.speech;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.core.*;
import java.io.File;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceInferenceTest {
    @Test public void wavToTextOnAndroidWithoutAnyNetworkCalls() throws Exception {
        Context fixtures = InstrumentationRegistry.getInstrumentation().getContext();
        // The model is always in the application APK; only sample audio is optional.
        Assume.assumeTrue("Supply voiceFixtures to include the test WAV asset",
                java.util.Arrays.asList(fixtures.getAssets().list("")).contains("test.wav"));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalModelManager models = new LocalModelManager(new File(app.getCacheDir(), "instrumentation-model"));
        models.install(app.getAssets().open("models/" + LocalModelManager.MODEL_NAME + ".zip"));
        UnifiedStreamingRecognizer recognizer = new UnifiedStreamingRecognizer();
        recognizer.load(models.modelDirectory());
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean partialSeen = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (StreamingTranscriptionSession session = new StreamingTranscriptionSession(recognizer, worker);
             WavFileAudioReceiver source = new WavFileAudioReceiver(() -> fixtures.getAssets().open("test.wav"), true)) {
            session.setReady(true);
            session.observe(state -> {
                if (state.phase == VoiceState.Phase.RECEIVING && !state.text.isEmpty()) partialSeen.set(true);
                if (state.phase == VoiceState.Phase.RESULT || state.phase == VoiceState.Phase.ERROR) done.countDown();
            });
            source.connect("", session);
            assertTrue("Device inference timed out", done.await(300, TimeUnit.SECONDS));
            VoiceState result = session.snapshot();
            assertEquals(result.message, VoiceState.Phase.RESULT, result.phase);
            // Parakeet may format spoken digits as numerals and add punctuation.
            assertTrue("Expected a nonempty transcription", !result.text.trim().isEmpty());
            assertTrue("No live text appeared before release; check phone streaming performance", partialSeen.get());
        }
    }
}
