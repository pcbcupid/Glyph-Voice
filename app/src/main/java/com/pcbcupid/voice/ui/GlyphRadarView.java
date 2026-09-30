package com.pcbcupid.voice.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.pcbcupid.voice.R;
import java.util.*;

/** Blips are actual scan results. Decorative positions are NOT bearing/distance estimates. */
public final class GlyphRadarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> ids = new ArrayList<>();
    private ValueAnimator sweep;
    private float angle;
    public GlyphRadarView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    public void devices(Collection<String> values) { ids.clear(); ids.addAll(values); invalidate(); }
    public void scanning(boolean active) {
        if (sweep != null) { sweep.cancel(); sweep = null; }
        if (active && ValueAnimator.areAnimatorsEnabled()) {
            sweep = ValueAnimator.ofFloat(0, 360); sweep.setDuration(3200); sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.setInterpolator(new LinearInterpolator()); sweep.addUpdateListener(a -> { angle = (float) a.getAnimatedValue(); invalidate(); }); sweep.start();
        }
        invalidate();
    }
    @Override protected void onDetachedFromWindow() { scanning(false); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f, y = getHeight() / 2f, radius = Math.min(x, y) - 20;
        int accent = getContext().getColor(R.color.accent), outline = getContext().getColor(R.color.outline);
        paint.setColor(getContext().getColor(R.color.soft_accent)); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, radius, paint);
        if (sweep != null) {
            paint.setShader(new SweepGradient(x, y, new int[]{Color.TRANSPARENT, Color.TRANSPARENT, (accent & 0xffffff) | 0x50000000}, new float[]{0, .72f, 1}));
            canvas.save(); canvas.rotate(angle, x, y); canvas.drawCircle(x, y, radius, paint); canvas.restore(); paint.setShader(null);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(outline);
        for (int i = 1; i <= 3; ++i) canvas.drawCircle(x, y, radius * i / 3, paint);
        canvas.drawLine(x - radius, y, x + radius, y, paint); canvas.drawLine(x, y - radius, x, y + radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(accent); canvas.drawCircle(x, y, 7, paint);
        for (String id : ids) {
            long hash = Integer.toUnsignedLong(id.hashCode()); double direction = Math.toRadians(hash % 360);
            float r = radius * (.35f + (hash % 40) / 100f);
            float bx = x + (float) Math.cos(direction) * r, by = y + (float) Math.sin(direction) * r;
            paint.setColor((accent & 0xffffff) | 0x25000000); canvas.drawCircle(bx, by, 15, paint);
            paint.setColor(accent); canvas.drawCircle(bx, by, 6, paint);
        }
    }
}
