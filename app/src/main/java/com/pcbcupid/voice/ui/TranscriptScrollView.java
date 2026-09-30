package com.pcbcupid.voice.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Give drags inside the fixed-height text panel to it, not the outer page. */
public final class TranscriptScrollView extends ScrollView {
    public TranscriptScrollView(Context context, AttributeSet attributes) { super(context, attributes); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }
}
