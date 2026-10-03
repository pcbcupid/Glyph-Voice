package com.pcbcupid.voice.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** UI-only checks: no model loading, board connection, or OS permission requests. */
@RunWith(AndroidJUnit4.class)
public class ManualConnectionTest {
    @Test public void manifestNeedsNeitherBluetoothNorLocation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        for (String permission : info.requestedPermissions) {
            assertFalse(permission, permission.contains("BLUETOOTH"));
            assertFalse(permission, permission.contains("LOCATION"));
        }
    }

    @Test public void manualDialogRemembersIpAndKeepsInvalidInputForCorrection() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("glyph", 0);
        String previous = preferences.getString("address", null);
        preferences.edit().putString("address", "192.168.0.126:8080").commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showConnect");
                    show.setAccessible(true);
                    show.invoke(activity);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            onView(withContentDescription("Glyph address")).check(matches(withText("192.168.0.126:8080")));
            onView(withContentDescription("Glyph address")).perform(replaceText("8.8.8.8"), closeSoftKeyboard());
            onView(withText("Connect")).perform(click());
            onView(withContentDescription("Glyph address")).check(matches(isDisplayed()));
            assertEquals("192.168.0.126:8080", preferences.getString("address", ""));
            onView(withText("Wi-Fi setup help")).perform(click());
            onView(withText("Set up Glyph Wi-Fi")).check(matches(isDisplayed()));
        } finally {
            android.content.SharedPreferences.Editor edit = preferences.edit();
            if (previous == null) edit.remove("address"); else edit.putString("address", previous);
            edit.commit();
        }
    }
}
