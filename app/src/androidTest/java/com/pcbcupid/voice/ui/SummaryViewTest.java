package com.pcbcupid.voice.ui;

import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import com.pcbcupid.voice.data.ConversationStore.Entry;
import com.pcbcupid.voice.service.VoiceService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Bound-only service: never connects, loads the speech model, or calls an API. */
@RunWith(AndroidJUnit4.class)
public class SummaryViewTest {
    @Rule public final ServiceTestRule service = new ServiceTestRule();

    private VoiceController controller() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), VoiceService.class);
        return ((VoiceService.LocalBinder) service.bindService(intent)).controller();
    }
    private Entry summary() {
        return new Entry("view-test-summary", "A natural summary.", "Complete", 1,
                "view-test-source", "All the original words.", "DeepSeek", "deepseek-flash");
    }

    @Test public void originalAndSummaryToggleRepeatedlyWithoutNewRequest() throws Exception {
        VoiceController controller = controller();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(controller.selectSummary(summary()));
            for (int i = 0; i < 3; i++) {
                assertTrue(controller.viewingSummary());
                assertEquals("A natural summary.", controller.displayedText());
                controller.showOriginalOrCurrent();
                assertTrue(controller.viewingOriginal());
                assertFalse(controller.viewingSummary());
                assertEquals("All the original words.", controller.displayedText());
                assertEquals("All the original words.", controller.summarySource().text);
                controller.showOriginalOrCurrent();
            }
            assertTrue(controller.viewingSummary());
            assertFalse(controller.summaryBusy);
        });
    }

    @Test public void otherHistoryOrCurrentSelectionClearsPreviousSummaryPair() throws Exception {
        VoiceController controller = controller();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controller.selectSummary(summary());
            controller.showOriginalOrCurrent();
            controller.selectConversation(new Entry("other", "Different words.", "Complete", 2));
            assertFalse(controller.viewingOriginal());
            controller.showOriginalOrCurrent();
            assertFalse(controller.viewingHistory());
            controller.selectSummary(summary());
            controller.showOriginalOrCurrent();
            controller.showCurrent();
            controller.showOriginalOrCurrent();
            assertFalse(controller.viewingHistory());
            assertFalse(controller.viewingOriginal());
        });
    }
}
