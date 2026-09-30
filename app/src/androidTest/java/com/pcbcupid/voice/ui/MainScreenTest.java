package com.pcbcupid.voice.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.pcbcupid.voice.R;
import com.pcbcupid.voice.audio.AudioReceiver.Connection;
import com.pcbcupid.voice.core.VoiceState;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainScreenTest {
    @Test public void transcriptHasBoundedScrollableContentAndRowsHaveDeleteActions() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.view.View scroll = activity.findViewById(R.id.transcript_scroll);
                TextView text = activity.findViewById(R.id.transcript);
                assertTrue(scroll instanceof TranscriptScrollView);
                assertSame(scroll, text.getParent());
                assertTrue(scroll.getLayoutParams().height > 0); // Fixed viewport, not wrap_content.
                android.view.View row = activity.getLayoutInflater().inflate(R.layout.history_row, null);
                assertNotNull(row.findViewById(R.id.history_delete));
                assertNotNull(row.findViewById(R.id.history_open));
                android.widget.Button api = activity.findViewById(R.id.api_settings);
                android.widget.Button about = activity.findViewById(R.id.licenses);
                assertTrue(api.isClickable());
                assertTrue(about.isClickable());
                assertNotNull(api.getCompoundDrawablesRelative()[0]);
                assertNotNull(about.getCompoundDrawablesRelative()[0]);
                android.widget.Button toggle = activity.findViewById(R.id.show_current);
                assertNotNull(toggle.getCompoundDrawablesRelative()[0]);
            });
        }
    }
    @Test public void statesAreReadableAndNotColorOnly() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Render and inspect within one UI turn. Never create a second,
            // activity-owned controller alongside the real service controller.
            scenario.onActivity(activity -> {
            for (Connection connection : Connection.values()) {
                for (VoiceState.Phase phase : VoiceState.Phase.values()) {
                    activity.renderState(new VoiceState(connection, phase,
                            "A useful transcription", phase.name(), 10, 32000, 16000, 1, 24));
                    assertEquals("A useful transcription", ((TextView) activity.findViewById(R.id.transcript)).getText().toString());
                    assertEquals(phase.name(), ((TextView) activity.findViewById(R.id.status)).getText().toString());
                    assertTrue(((TextView) activity.findViewById(R.id.connection)).getText().toString().contains(
                            connection == Connection.CONNECTED ? "connected" :
                            connection == Connection.CONNECTING ? "Connecting" :
                            connection == Connection.RECONNECTING ? "Reconnecting" : "Disconnected"));
                }
            }
            });
        }
    }
    @Test public void drawersAreRealOppositeSidePanelsAndMenusHaveAccessibleNames() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                androidx.drawerlayout.widget.DrawerLayout drawers = activity.findViewById(R.id.drawers);
                assertEquals("Open conversations on the left", activity.findViewById(R.id.conversations).getContentDescription());
                assertEquals("Open summaries on the right", activity.findViewById(R.id.summaries).getContentDescription());
                drawers.openDrawer(android.view.Gravity.LEFT, false);
                assertTrue(drawers.isDrawerOpen(android.view.Gravity.LEFT));
                drawers.closeDrawer(android.view.Gravity.LEFT, false);
                drawers.openDrawer(android.view.Gravity.RIGHT, false);
                assertTrue(drawers.isDrawerOpen(android.view.Gravity.RIGHT));
                assertFalse(drawers.isDrawerOpen(android.view.Gravity.LEFT));
            });
        }
    }
}
