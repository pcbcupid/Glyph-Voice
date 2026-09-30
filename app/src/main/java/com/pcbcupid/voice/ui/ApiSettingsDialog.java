package com.pcbcupid.voice.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.pcbcupid.voice.ai.AiProvider;

/** No key is ever prefilled, copied to saved instance state or shown in errors. */
final class ApiSettingsDialog {
    static AlertDialog create(Activity activity, VoiceController controller) {
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, 0, padding, 0);
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        Spinner providers = new Spinner(activity);
        providers.setContentDescription("AI provider");
        ArrayAdapter<AiProvider> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, AiProvider.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providers.setAdapter(adapter);
        providers.setSelection(controller.api.provider().ordinal());
        EditText key = new EditText(activity);
        key.setSingleLine(true); key.setSaveEnabled(false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setContentDescription("API key for selected provider");
        EditText model = new EditText(activity);
        model.setSingleLine(true); model.setSaveEnabled(false);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setContentDescription("Provider model ID");
        TextView error = new TextView(activity);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        for (View view : new View[]{providers, key, model, error}) form.addView(view, new LinearLayout.LayoutParams(-1, -2));
        providers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AiProvider provider = AiProvider.values()[position];
                key.setText("");
                key.setHint(controller.api.hasKey(provider) ? "Key saved · leave blank to keep" : provider.label + " API key");
                model.setText(controller.api.model(provider));
                error.setText("");
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Connect your API")
                .setMessage("Choose a provider and paste your own API key. Keys are encrypted on this phone. "
                        + "With a key saved, stopping each new recording automatically uploads its final transcript for a summary, even while locked. "
                        + "Summarize during recording stops the Glyph first; on saved text it sends immediately. Audio is never uploaded. "
                        + "Internet and provider billing are required; provider retention policies apply. "
                        + "Saving a key does not test it or send a request. Model IDs may be changed below.")
                .setView(scroll).setPositiveButton("Save", null).setNeutralButton("Remove key", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
                dialog.getButton(button).setOnClickListener(v -> {
                    for (int b : new int[]{-1, -2, -3}) dialog.getButton(b).setEnabled(false);
                    providers.setEnabled(false); key.setEnabled(false); model.setEnabled(false);
                    controller.saveApi((AiProvider) providers.getSelectedItem(), key.getText().toString(),
                            model.getText().toString(), button == AlertDialog.BUTTON_NEUTRAL, message -> {
                                if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) return;
                                if (message.isEmpty()) {
                                    key.setText(""); dialog.dismiss();
                                    Toast.makeText(activity, button == AlertDialog.BUTTON_NEUTRAL ? "Key removed" : "API saved. New recordings summarize on stop; tap Summarize for existing text.", Toast.LENGTH_LONG).show();
                                } else {
                                    error.setText(message);
                                    for (int b : new int[]{-1, -2, -3}) dialog.getButton(b).setEnabled(true);
                                    providers.setEnabled(true); key.setEnabled(true); model.setEnabled(true);
                                }
                            });
                });
            }
        });
        dialog.setOnDismissListener(ignored -> key.setText(""));
        return dialog;
    }
}
