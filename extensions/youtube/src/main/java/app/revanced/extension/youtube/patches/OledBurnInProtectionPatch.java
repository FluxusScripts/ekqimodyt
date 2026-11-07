package app.revanced.extension.youtube.patches;

import static app.revanced.extension.shared.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.youtube.settings.Settings;

/**
 * Moves static Shorts controls by a few pixels to reduce uneven OLED wear.
 */
@SuppressWarnings("unused")
public final class OledBurnInProtectionPatch {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Weak keys and values keep a detached Shorts hierarchy collectible.
     */
    private static final Map<ViewGroup, WeakReference<Controller>> CONTROLLERS =
            new WeakHashMap<>();

    private OledBurnInProtectionPatch() {
    }

    /**
     * Injection point called when YouTube creates a Shorts player view.
     */
    public static void onShortsCreate(@Nullable View view) {
        if (!(view instanceof ViewGroup root)) return;

        Runnable registerController = () -> {
            try {
                WeakReference<Controller> reference = CONTROLLERS.get(root);
                Controller existing = reference == null ? null : reference.get();
                if (existing != null) return;

                Controller controller = new Controller(root);
                CONTROLLERS.put(root, new WeakReference<>(controller));
                root.addOnAttachStateChangeListener(controller);

                if (root.isAttachedToWindow()) {
                    controller.onViewAttachedToWindow(root);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to initialize OLED protection", ex);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            registerController.run();
        } else {
            MAIN_HANDLER.post(registerController);
        }
    }

    private static final class Controller implements View.OnAttachStateChangeListener {
        private static final int[][] SHIFT_PATTERN = {
                {-1, -1},
                {0, -1},
                {1, -1},
                {1, 0},
                {1, 1},
                {0, 1},
                {-1, 1},
                {-1, 0},
                {0, 0},
        };

        private final ViewGroup root;
        private final Map<View, AppliedOffset> shiftedViews = new IdentityHashMap<>();
        private final Runnable shiftRunnable = this::runShiftCycle;

        @Nullable
        private ProtectionButton button;
        private boolean attached;
        private int patternIndex;
        private float buttonMarginPx;

        Controller(@NonNull ViewGroup root) {
            this.root = root;
            patternIndex = Math.floorMod(System.identityHashCode(root), SHIFT_PATTERN.length);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            attached = true;
            root.post(this::refreshFromSettings);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            attached = false;
            MAIN_HANDLER.removeCallbacks(shiftRunnable);
            restoreShiftedViews();
            resetButtonPosition();
        }

        private void refreshFromSettings() {
            MAIN_HANDLER.removeCallbacks(shiftRunnable);
            try {
                ensureButtonState();

                ProtectionButton currentButton = button;
                if (currentButton != null) {
                    currentButton.setProtectionEnabled(Settings.OLED_PROTECTION_ENABLED.get());
                    root.bringChildToFront(currentButton);
                }

                if (!attached || !root.isAttachedToWindow()
                        || !Settings.OLED_PROTECTION_ENABLED.get()) {
                    restoreShiftedViews();
                    resetButtonPosition();
                    return;
                }

                runShiftCycle();
            } catch (Exception ex) {
                restoreShiftedViews();
                resetButtonPosition();
                Logger.printException(() -> "Failed to refresh OLED protection", ex);
            }
        }

        private void ensureButtonState() {
            boolean shouldShow = Settings.OLED_PROTECTION_SHOW_BUTTON.get();

            if (!shouldShow) {
                if (button != null) {
                    root.removeView(button);
                    button = null;
                }
                return;
            }

            if (button != null) return;

            final float density = root.getResources().getDisplayMetrics().density;
            final int size = Math.round(44 * density);
            buttonMarginPx = Math.round(12 * density);

            ProtectionButton newButton = new ProtectionButton(root);
            newButton.setProtectionEnabled(Settings.OLED_PROTECTION_ENABLED.get());
            newButton.setOnClickListener(clickedView -> {
                boolean enabled = !Settings.OLED_PROTECTION_ENABLED.get();
                Settings.OLED_PROTECTION_ENABLED.save(enabled);
                newButton.setProtectionEnabled(enabled);
                refreshFromSettings();
                Utils.showToastShort(str(enabled
                        ? "revanced_oled_protection_toast_on"
                        : "revanced_oled_protection_toast_off"));
            });

            root.addView(newButton, size, size);
            button = newButton;
            resetButtonPosition();
            root.bringChildToFront(newButton);
        }

        private void runShiftCycle() {
            if (!attached || !root.isAttachedToWindow()
                    || !Settings.OLED_PROTECTION_ENABLED.get()) {
                restoreShiftedViews();
                resetButtonPosition();
                return;
            }

            try {
                ensureButtonState();

                int shiftDp = clamp(Settings.OLED_PROTECTION_SHIFT_DP.get(), 1, 12);
                float shiftPx = shiftDp * root.getResources().getDisplayMetrics().density;
                int[] position = SHIFT_PATTERN[patternIndex];
                patternIndex = (patternIndex + 1) % SHIFT_PATTERN.length;

                float offsetX = position[0] * shiftPx;
                float offsetY = position[1] * shiftPx;
                applyShift(offsetX, offsetY);

                int intervalSeconds = clamp(
                        Settings.OLED_PROTECTION_INTERVAL_SECONDS.get(),
                        15,
                        600
                );
                MAIN_HANDLER.postDelayed(shiftRunnable, intervalSeconds * 1000L);
            } catch (Exception ex) {
                restoreShiftedViews();
                resetButtonPosition();
                Logger.printException(() -> "Failed to shift Shorts controls", ex);
            }
        }

        private void applyShift(float offsetX, float offsetY) {
            restoreShiftedViews();

            List<View> candidates = new ArrayList<>();
            for (int index = 0; index < root.getChildCount(); index++) {
                collectMovableBranches(root.getChildAt(index), candidates);
            }

            for (View candidate : candidates) {
                candidate.setTranslationX(candidate.getTranslationX() + offsetX);
                candidate.setTranslationY(candidate.getTranslationY() + offsetY);
                shiftedViews.put(candidate, new AppliedOffset(offsetX, offsetY));
            }

            ProtectionButton currentButton = button;
            if (currentButton != null) {
                currentButton.setTranslationX(buttonMarginPx + offsetX);
                currentButton.setTranslationY(buttonMarginPx + offsetY);
                root.bringChildToFront(currentButton);
            }
        }

        private void collectMovableBranches(@Nullable View view, List<View> output) {
            if (view == null || view == button || view.getVisibility() != View.VISIBLE) return;
            if (isMediaRenderingView(view)) return;

            if (view instanceof ViewGroup group && containsMediaRenderingView(group)) {
                for (int index = 0; index < group.getChildCount(); index++) {
                    collectMovableBranches(group.getChildAt(index), output);
                }
                return;
            }

            if (view.getWidth() > 0 && view.getHeight() > 0) {
                output.add(view);
            }
        }

        private boolean containsMediaRenderingView(@NonNull ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (isMediaRenderingView(child)) return true;
                if (child instanceof ViewGroup childGroup
                        && containsMediaRenderingView(childGroup)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isMediaRenderingView(@NonNull View view) {
            if (view instanceof SurfaceView || view instanceof TextureView) return true;

            String className = view.getClass().getName();
            return className.contains("SurfaceView") || className.contains("TextureView");
        }

        private void restoreShiftedViews() {
            for (Map.Entry<View, AppliedOffset> entry : shiftedViews.entrySet()) {
                View view = entry.getKey();
                AppliedOffset offset = entry.getValue();
                view.setTranslationX(view.getTranslationX() - offset.x);
                view.setTranslationY(view.getTranslationY() - offset.y);
            }
            shiftedViews.clear();
        }

        private void resetButtonPosition() {
            ProtectionButton currentButton = button;
            if (currentButton != null) {
                currentButton.setTranslationX(buttonMarginPx);
                currentButton.setTranslationY(buttonMarginPx);
            }
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    private static final class AppliedOffset {
        final float x;
        final float y;

        AppliedOffset(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Resource-independent shield button, so it can be safely injected into obfuscated layouts.
     */
    private static final class ProtectionButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shieldPath = new Path();
        private boolean protectionEnabled;

        ProtectionButton(@NonNull ViewGroup root) {
            super(root.getContext());
            setClickable(true);
            setFocusable(true);
            setContentDescription(str("revanced_oled_protection_button_content_description"));
            setTooltipText(str("revanced_oled_protection_button_content_description"));
            setElevation(6 * root.getResources().getDisplayMetrics().density);
        }

        void setProtectionEnabled(boolean enabled) {
            protectionEnabled = enabled;
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f;
            float radius = Math.min(width, height) * 0.45f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isPressed() ? 0xD9000000 : 0xB8000000);
            canvas.drawCircle(centerX, centerY, radius, paint);

            float shieldWidth = radius * 0.92f;
            float shieldHeight = radius * 1.18f;
            float left = centerX - shieldWidth / 2f;
            float right = centerX + shieldWidth / 2f;
            float top = centerY - shieldHeight / 2f;
            float bottom = centerY + shieldHeight / 2f;

            shieldPath.reset();
            shieldPath.moveTo(centerX, top);
            shieldPath.lineTo(right, top + shieldHeight * 0.18f);
            shieldPath.lineTo(right - shieldWidth * 0.08f, bottom - shieldHeight * 0.30f);
            shieldPath.quadTo(centerX, bottom, left + shieldWidth * 0.08f,
                    bottom - shieldHeight * 0.30f);
            shieldPath.lineTo(left, top + shieldHeight * 0.18f);
            shieldPath.close();

            paint.setStrokeWidth(Math.max(2f, radius * 0.12f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);

            if (protectionEnabled) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(0, 200, 160));
                canvas.drawPath(shieldPath, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                float checkY = centerY + shieldHeight * 0.02f;
                canvas.drawLine(centerX - shieldWidth * 0.23f, checkY,
                        centerX - shieldWidth * 0.05f, checkY + shieldHeight * 0.15f, paint);
                canvas.drawLine(centerX - shieldWidth * 0.05f, checkY + shieldHeight * 0.15f,
                        centerX + shieldWidth * 0.25f, checkY - shieldHeight * 0.17f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xE6FFFFFF);
                canvas.drawPath(shieldPath, paint);

                paint.setColor(0xFFFF5A5F);
                canvas.drawLine(left, bottom, right, top, paint);
            }
        }
    }
}
