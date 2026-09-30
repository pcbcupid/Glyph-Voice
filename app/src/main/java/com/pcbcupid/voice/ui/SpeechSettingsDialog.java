package com.pcbcupid.voice.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.*;

public final class SpeechSettingsDialog {
    private SpeechSettingsDialog() { }
    public static AlertDialog create(Activity activity, VoiceController controller) {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        Switch cloud = new Switch(activity); cloud.setText("Use cloud speech-to-text"); cloud.setChecked(controller.speech.cloud());
        EditText url = field(activity, "Full HTTPS transcription URL", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(controller.speech.endpoint());
        EditText model = field(activity, "Speech model ID", InputType.TYPE_CLASS_TEXT);
        model.setText(controller.speech.model());
        EditText key = field(activity, controller.speech.hasKey() ? "API key saved · blank keeps it for this URL" : "API key (optional)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        CheckBox clear = new CheckBox(activity); clear.setText("Remove saved speech API key");
        TextView error = new TextView(activity);
        for (View v : new View[]{cloud, url, model, key, clear, error}) form.addView(v);
        Runnable toggle = () -> { url.setEnabled(cloud.isChecked()); model.setEnabled(cloud.isChecked()); key.setEnabled(cloud.isChecked()); };
        cloud.setOnCheckedChangeListener((v, checked) -> toggle.run()); toggle.run();
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Speech recognition")
                .setMessage("Local is the default and works offline. Cloud sends microphone audio to your URL in 15-second chunks while recording, plus the remaining audio when you stop. Text appears after each response. Internet and provider charges may apply. Audio is held in memory, not saved to files.\n\nUse an OpenAI-compatible multipart transcription endpoint, for example https://your-provider/v1/audio/transcriptions. Keys are encrypted on this phone. Changing URL clears the old key. Saving makes no network request.")
                .setView(scroll).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            controller.saveSpeech(cloud.isChecked(), url.getText().toString(), model.getText().toString(),
                    key.getText().toString(), clear.isChecked(), message -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (message.isEmpty()) dialog.dismiss();
                        else { error.setText(message); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                    });
        }));
        dialog.setOnDismissListener(ignored -> key.setText(""));
        return dialog;
    }
    private static EditText field(Activity activity, String label, int input) {
        EditText view = new EditText(activity); view.setSingleLine(true); view.setSaveEnabled(false);
        view.setHint(label); view.setContentDescription(label); view.setInputType(input); return view;
    }
}
