package com.pcbcupid.voice.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;

/** UI-only checks: intentionally no Bluetooth scan, pairing, network or speech inference. */
@RunWith(AndroidJUnit4.class)
public class GlyphSetupScreenTest {
    @Test public void setupWaitsForUserAndHasAccessibleFallback() {
        try (ActivityScenario<GlyphSetupActivity> ignored = ActivityScenario.launch(GlyphSetupActivity.class)) {
            onView(withText("Find your Glyph")).check(matches(isDisplayed()));
            onView(withText("Scan nearby")).check(matches(isEnabled()));
            onView(withContentDescription("Wi-Fi password")).check(matches(not(isDisplayed())));
            onView(withText("iPhone / Wi-Fi setup fallback")).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            onView(withText("Wi-Fi setup fallback")).check(matches(isDisplayed()));
            onView(withText("OK")).perform(click());
        }
    }
}
