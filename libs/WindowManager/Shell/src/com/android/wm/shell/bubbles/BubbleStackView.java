/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.bubbles;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.wm.shell.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_STACK_VIEW;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.bubbles.animation.AnimatableScaleMatrix;
import com.android.wm.shell.bubbles.animation.ExpandedAnimationController;
import com.android.wm.shell.bubbles.animation.PhysicsAnimationLayout;
import com.android.wm.shell.bubbles.animation.StackAnimationController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Renders bubbles in a stack and handles animating expanded and collapsed states.
 */
public class BubbleStackView extends FrameLayout
        implements ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleStackView" : TAG_BUBBLES;

    /** How far the flyout needs to be dragged before it's dismissed regardless of velocity. */
    static final float FLYOUT_DRAG_PERCENT_DISMISS = 0.25f;

    /** Velocity required to dismiss the flyout via drag. */
    private static final float FLYOUT_DISMISS_VELOCITY = 2000f;

    /**
     * Factor for attenuating translation when the flyout is overscrolled (8f = flyout moves 1 pixel
     * for every 8 pixels overscrolled).
     */
    private static final float FLYOUT_OVERSCROLL_ATTENUATION_FACTOR = 8f;

    /** Duration of the flyout alpha animations. */
    private static final int FLYOUT_ALPHA_ANIMATION_DURATION = 100;

    private static final int FADE_IN_DURATION = 320;

    /** Percent to darken the bubbles when they're in the dismiss target. */
    private static final float DARKEN_PERCENT = 0.3f;

    /** How long to wait, in milliseconds, before hiding the flyout. */
    @VisibleForTesting
    static final int FLYOUT_HIDE_AFTER = 5000;

    private static final float EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT = 0.1f;

    private static final int EXPANDED_VIEW_ALPHA_ANIMATION_DURATION = 150;

    /**
     * How long to wait to animate the stack temporarily invisible after a drag/flyout hide
     * animation ends, if we are in fact temporarily invisible.
     */
    private static final int ANIMATE_TEMPORARILY_INVISIBLE_DELAY = 1000;

    private static final PhysicsAnimator.SpringConfig FLYOUT_IME_ANIMATION_SPRING_CONFIG =
            new PhysicsAnimator.SpringConfig(
                    StackAnimationController.IME_ANIMATION_STIFFNESS,
                    StackAnimationController.DEFAULT_BOUNCINESS);

    private final PhysicsAnimator.SpringConfig mScaleInSpringConfig =
            new PhysicsAnimator.SpringConfig(300f, 0.9f);

    private final PhysicsAnimator.SpringConfig mScaleOutSpringConfig =
            new PhysicsAnimator.SpringConfig(900f, 1f);

    private final PhysicsAnimator.SpringConfig mTranslateSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    SpringForce.STIFFNESS_VERY_LOW, SpringForce.DAMPING_RATIO_NO_BOUNCY);

    /**
     * Handler to use for all delayed animations - this way, we can easily cancel them before
     * starting a new animation.
     */
    private final ShellExecutor mDelayedAnimationExecutor;
    private Runnable mDelayedAnimation;

    /**
     * Interface to synchronize {@link View} state and the screen.
     *
     * {@hide}
     */
    public interface SurfaceSynchronizer {
        /**
         * Wait until requested change on a {@link View} is reflected on the screen.
         *
         * @param callback callback to run after the change is reflected on the screen.
         */
        void syncSurfaceAndRun(Runnable callback);
    }

    private static final SurfaceSynchronizer DEFAULT_SURFACE_SYNCHRONIZER =
            new SurfaceSynchronizer() {
        @Override
        public void syncSurfaceAndRun(Runnable callback) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                // Just wait 2 frames. There is no guarantee, but this is usually enough time that
                // the requested change is reflected on the screen.
                // TODO: Once SurfaceFlinger provide APIs to sync the state of {@code View} and
                // surfaces, rewrite this logic with them.
                private int mFrameWait = 2;

                @Override
                public void doFrame(long frameTimeNanos) {
                    if (--mFrameWait > 0) {
                        Choreographer.getInstance().postFrameCallback(this);
                    } else {
                        callback.run();
                    }
                }
            });
        }
    };
    private final BubbleController mBubbleController;
    private final BubbleData mBubbleData;

    private final ValueAnimator mDesaturateAndDarkenAnimator;
    private final Paint mDesaturateAndDarkenPaint = new Paint();

    private PhysicsAnimationLayout mBubbleContainer;
    private StackAnimationController mStackAnimationController;
    private ExpandedAnimationController mExpandedAnimationController;

    private View mTaskbarScrim;
    private FrameLayout mExpandedViewContainer;

    /** Matrix used to scale the expanded view container with a given pivot point. */
    private final AnimatableScaleMatrix mExpandedViewContainerMatrix = new AnimatableScaleMatrix();

    /**
     * SurfaceView that we draw screenshots of animating-out bubbles into. This allows us to animate
     * between bubble activities without needing both to be alive at the same time.
     */
    private SurfaceView mAnimatingOutSurfaceView;

    /** Container for the animating-out SurfaceView. */
    private FrameLayout mAnimatingOutSurfaceContainer;

    /** Animator for animating the alpha value of the animating out SurfaceView. */
    private final ValueAnimator mAnimatingOutSurfaceAlphaAnimator = ValueAnimator.ofFloat(0f, 1f);

    /**
     * Buffer containing a screenshot of the animating-out bubble. This is drawn into the
     * SurfaceView during animations.
     */
    private SurfaceControl.ScreenshotHardwareBuffer mAnimatingOutBubbleBuffer;

    private BubbleFlyoutView mFlyout;
    /** Runnable that fades out the flyout and then sets it to GONE. */
    private Runnable mHideFlyout = () -> animateFlyoutCollapsed(true, 0 /* velX */);
    /**
     * Callback to run after the flyout hides. Also called if a new flyout is shown before the
     * previous one animates out.
     */
    private Runnable mAfterFlyoutHidden;
    /**
     * Set when the flyout is tapped, so that we can expand the bubble associated with the flyout
     * once it collapses.
     */
    @Nullable
    private BubbleViewProvider mBubbleToExpandAfterFlyoutCollapse = null;

    /** Layout change listener that moves the stack to the nearest valid position on rotation. */
    private OnLayoutChangeListener mOrientationChangedListener;

    @Nullable private RelativeStackPosition mRelativeStackPositionBeforeRotation;

    private int mMaxBubbles;
    private int mBubbleSize;
    private int mBubbleElevation;
    private int mBubbleTouchPadding;
    private int mExpandedViewPadding;
    private int mCornerRadius;
    private int mImeOffset;
    @Nullable private BubbleViewProvider mExpandedBubble;
    private boolean mIsExpanded;

    /** Whether the stack is currently on the left side of the screen, or animating there. */
    private boolean mStackOnLeftOrWillBe = true;

    /** Whether a touch gesture, such as a stack/bubble drag or flyout drag, is in progress. */
    private boolean mIsGestureInProgress = false;

    /** Whether or not the stack is temporarily invisible off the side of the screen. */
    private boolean mTemporarilyInvisible = false;

    /** Whether we're in the middle of dragging the stack around by touch. */
    private boolean mIsDraggingStack = false;

    /** Whether the expanded view has been hidden, because we are dragging out a bubble. */
    private boolean mExpandedViewTemporarilyHidden = false;

    /** Animator for animating the expanded view's alpha (including the TaskView inside it). */
    private final ValueAnimator mExpandedViewAlphaAnimator = ValueAnimator.ofFloat(0f, 1f);

    /**
     * The pointer index of the ACTION_DOWN event we received prior to an ACTION_UP. We'll ignore
     * touches from other pointer indices.
     */
    private int mPointerIndexDown = -1;

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Stack view state:");

        String bubblesOnScreen = BubbleDebugConfig.formatBubblesString(
                getBubblesOnScreen(), getExpandedBubble());
        pw.print("  bubbles on screen:       "); pw.println(bubblesOnScreen);
        pw.print("  gestureInProgress:       "); pw.println(mIsGestureInProgress);
        pw.print("  showingDismiss:          "); pw.println(mDismissView.isShowing());
        pw.print("  isExpansionAnimating:    "); pw.println(mIsExpansionAnimating);
        pw.print("  expandedContainerVis:    "); pw.println(mExpandedViewContainer.getVisibility());
        pw.print("  expandedContainerAlpha:  "); pw.println(mExpandedViewContainer.getAlpha());
        pw.print("  expandedContainerMatrix: ");
        pw.println(mExpandedViewContainer.getAnimationMatrix());

        mStackAnimationController.dump(fd, pw, args);
        mExpandedAnimationController.dump(fd, pw, args);

        if (mExpandedBubble != null) {
            pw.println("Expanded bubble state:");
            pw.println("  expandedBubbleKey: " + mExpandedBubble.getKey());

            final BubbleExpandedView expandedView = mExpandedBubble.getExpandedView();

            if (expandedView != null) {
                pw.println("  expandedViewVis:    " + expandedView.getVisibility());
                pw.println("  expandedViewAlpha:  " + expandedView.getAlpha());
                pw.println("  expandedViewTaskId: " + expandedView.getTaskId());

                final View av = expandedView.getTaskView();

                if (av != null) {
                    pw.println("  activityViewVis:    " + av.getVisibility());
                    pw.println("  activityViewAlpha:  " + av.getAlpha());
                } else {
                    pw.println("  activityView is null");
                }
            } else {
                pw.println("Expanded bubble view state: expanded bubble view is null");
            }
        } else {
            pw.println("Expanded bubble state: expanded bubble is null");
        }
    }

    private Bubbles.BubbleExpandListener mExpandListener;

    /** Callback to run when we want to unbubble the given notification's conversation. */
    private Consumer<String> mUnbubbleConversationCallback;

    private boolean mViewUpdatedRequested = false;
    private boolean mIsExpansionAnimating = false;
    private boolean mIsBubbleSwitchAnimating = false;

    /** The view to desaturate/darken when magneted to the dismiss target. */
    @Nullable private View mDesaturateAndDarkenTargetView;

    private Rect mTempRect = new Rect();

    private final List<Rect> mSystemGestureExclusionRects = Collections.singletonList(new Rect());

    private ViewTreeObserver.OnPreDrawListener mViewUpdater =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
                    updateExpandedView();
                    mViewUpdatedRequested = false;
                    return true;
                }
            };

    private ViewTreeObserver.OnDrawListener mSystemGestureExcludeUpdater =
            this::updateSystemGestureExcludeRects;

    /** Float property that 'drags' the flyout. */
    private final FloatPropertyCompat mFlyoutCollapseProperty =
            new FloatPropertyCompat("FlyoutCollapseSpring") {
                @Override
                public float getValue(Object o) {
                    return mFlyoutDragDeltaX;
                }

                @Override
                public void setValue(Object o, float v) {
                    setFlyoutStateForDragLength(v);
                }
            };

    /** SpringAnimation that springs the flyout collapsed via onFlyoutDragged. */
    private final SpringAnimation mFlyoutTransitionSpring =
            new SpringAnimation(this, mFlyoutCollapseProperty);

    /** Distance the flyout has been dragged in the X axis. */
    private float mFlyoutDragDeltaX = 0f;

    /**
     * Runnable that animates in the flyout. This reference is needed to cancel delayed postings.
     */
    private Runnable mAnimateInFlyout;

    /**
     * End listener for the flyout spring that either posts a runnable to hide the flyout, or hides
     * it immediately.
     */
    private final DynamicAnimation.OnAnimationEndListener mAfterFlyoutTransitionSpring =
            (dynamicAnimation, b, v, v1) -> {
                if (mFlyoutDragDeltaX == 0) {
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                } else {
                    mFlyout.hideFlyout();
                }
            };

    @NonNull
    private final SurfaceSynchronizer mSurfaceSynchronizer;

    /**
     * The currently magnetized object, which is being dragged and will be attracted to the magnetic
     * dismiss target.
     *
     * This is either the stack itself, or an individual bubble.
     */
    private MagnetizedObject<?> mMagnetizedObject;

    /**
     * The MagneticTarget instance for our circular dismiss view. This is added to the
     * MagnetizedObject instances for the stack and any dragged-out bubbles.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;

    /** Magnet listener that handles animating and dismissing individual dragged-out bubbles. */
    private final MagnetizedObject.MagnetListener mIndividualBubbleMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    animateDesaturateAndDarken(
                            mExpandedAnimationController.getDraggedOutBubble(), false);

                    if (wasFlungOut) {
                        mExpandedAnimationController.snapBubbleBack(
                                mExpandedAnimationController.getDraggedOutBubble(), velX, velY);
                        mDismissView.hide();
                    } else {
                        mExpandedAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    if (mExpandedAnimationController.getDraggedOutBubble() == null) {
                        return;
                    }

                    mExpandedAnimationController.dismissDraggedOutBubble(
                            mExpandedAnimationController.getDraggedOutBubble() /* bubble */,
                            mDismissView.getHeight() /* translationYBy */,
                            BubbleStackView.this::dismissMagnetizedObject /* after */);
                    mDismissView.hide();
                }
            };

    /** Magnet listener that handles animating and dismissing the entire stack. */
    private final MagnetizedObject.MagnetListener mStackMagnetListener =
            new MagnetizedObject.MagnetListener() {
                @Override
                public void onStuckToTarget(
                        @NonNull MagnetizedObject.MagneticTarget target) {
                    animateDesaturateAndDarken(mBubbleContainer, true);
                }

                @Override
                public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                        float velX, float velY, boolean wasFlungOut) {
                    animateDesaturateAndDarken(mBubbleContainer, false);

                    if (wasFlungOut) {
                        mStackAnimationController.flingStackThenSpringToEdge(
                                mStackAnimationController.getStackPosition().x, velX, velY);
                        mDismissView.hide();
                    } else {
                        mStackAnimationController.onUnstuckFromTarget();
                    }
                }

                @Override
                public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                    mStackAnimationController.animateStackDismissal(
                            mDismissView.getHeight() /* translationYBy */,
                            () -> {
                                resetDesaturationAndDarken();
                                dismissMagnetizedObject();
                            }
                    );

                    mDismissView.hide();
                }
            };

    /**
     * Click listener set on each bubble view. When collapsed, clicking a bubble expands the stack.
     * When expanded, clicking a bubble either expands that bubble, or collapses the stack.
     */
    private OnClickListener mBubbleClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            mIsDraggingStack = false; // If the touch ended in a click, we're no longer dragging.

            // Bubble clicks either trigger expansion/collapse or a bubble switch, both of which we
            // shouldn't interrupt. These are quick transitions, so it's not worth trying to adjust
            // the animations inflight.
            if (mIsExpansionAnimating || mIsBubbleSwitchAnimating) {
                return;
            }

            final Bubble clickedBubble = mBubbleData.getBubbleWithView(view);

            // If the bubble has since left us, ignore the click.
            if (clickedBubble == null) {
                return;
            }

            final boolean clickedBubbleIsCurrentlyExpandedBubble =
                    clickedBubble.getKey().equals(mExpandedBubble.getKey());

            if (isExpanded()) {
                mExpandedAnimationController.onGestureFinished();
            }

            if (isExpanded() && !clickedBubbleIsCurrentlyExpandedBubble) {
                if (clickedBubble != mBubbleData.getSelectedBubble()) {
                    // Select the clicked bubble.
                    mBubbleData.setSelectedBubble(clickedBubble);
                } else {
                    // If the clicked bubble is the selected bubble (but not the expanded bubble),
                    // that means overflow was previously expanded. Set the selected bubble
                    // internally without going through BubbleData (which would ignore it since it's
                    // already selected).
                    setSelectedBubble(clickedBubble);
                }
            } else {
                // Otherwise, we either tapped the stack (which means we're collapsed
                // and should expand) or the currently selected bubble (we're expanded
                // and should collapse).
                if (!maybeShowStackEdu()) {
                    mBubbleData.setExpanded(!mBubbleData.isExpanded());
                }
            }
        }
    };

    /**
     * Touch listener set on each bubble view. This enables dragging and dismissing the stack (when
     * collapsed), or individual bubbles (when expanded).
     */
    private RelativeTouchListener mBubbleTouchListener = new RelativeTouchListener() {

        @Override
        public boolean onDown(@NonNull View v, @NonNull MotionEvent ev) {
            // If we're expanding or collapsing, consume but ignore all touch events.
            if (mIsExpansionAnimating) {
                return true;
            }

            // If the manage menu is visible, just hide it.
            if (mShowingManage) {
                showManageMenu(false /* show */);
            }

            if (mBubbleData.isExpanded()) {
                if (mManageEduView != null) {
                    mManageEduView.hide(false /* show */);
                }

                // If we're expanded, tell the animation controller to prepare to drag this bubble,
                // dispatching to the individual bubble magnet listener.
                mExpandedAnimationController.prepareForBubbleDrag(
                        v /* bubble */,
                        mMagneticTarget,
                        mIndividualBubbleMagnetListener);

                hideCurrentInputMethod();

                // Save the magnetized individual bubble so we can dispatch touch events to it.
                mMagnetizedObject = mExpandedAnimationController.getMagnetizedBubbleDraggingOut();
            } else {
                // If we're collapsed, prepare to drag the stack. Cancel active animations, set the
                // animation controller, and hide the flyout.
                mStackAnimationController.cancelStackPositionAnimations();
                mBubbleContainer.setActiveController(mStackAnimationController);
                hideFlyoutImmediate();

                if (!mPositioner.showingInTaskbar()) {
                    // Also, save the magnetized stack so we can dispatch touch events to it.
                    mMagnetizedObject = mStackAnimationController.getMagnetizedStack(
                            mMagneticTarget);
                    mMagnetizedObject.setMagnetListener(mStackMagnetListener);
                } else {
                    // In taskbar, the stack isn't draggable so we shouldn't dispatch touch events.
                    mMagnetizedObject = null;
                }

                // Also, save the magnetized stack so we can dispatch touch events to it.
                mMagnetizedObject = mStackAnimationController.getMagnetizedStack(mMagneticTarget);
                mMagnetizedObject.setMagnetListener(mStackMagnetListener);

                mIsDraggingStack = true;

                // Cancel animations to make the stack temporarily invisible, since we're now
                // dragging it.
                updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
            }

            passEventToMagnetizedObject(ev);

            // Bubbles are always interested in all touch events!
            return true;
        }

        @Override
        public void onMove(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy) {
            // If we're expanding or collapsing, ignore all touch events.
            if (mIsExpansionAnimating
                    // Also ignore events if we shouldn't be draggable.
                    || (mPositioner.showingInTaskbar() && !mIsExpanded)) {
                return;
            }

            // Show the dismiss target, if we haven't already.
            mDismissView.show();

            if (mIsExpanded && mExpandedBubble != null && v.equals(mExpandedBubble.getIconView())) {
                // Hide the expanded view if we're dragging out the expanded bubble, and we haven't
                // already hidden it.
                hideExpandedViewIfNeeded();
            }

            // First, see if the magnetized object consumes the event - if so, we shouldn't move the
            // bubble since it's stuck to the target.
            if (!passEventToMagnetizedObject(ev)) {
                if (mBubbleData.isExpanded() || mPositioner.showingInTaskbar()) {
                    mExpandedAnimationController.dragBubbleOut(
                            v, viewInitialX + dx, viewInitialY + dy);
                } else {
                    if (mStackEduView != null) {
                        mStackEduView.hide(false /* fromExpansion */);
                    }
                    mStackAnimationController.moveStackFromTouch(
                            viewInitialX + dx, viewInitialY + dy);
                }
            }
        }

        @Override
        public void onUp(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy, float velX, float velY) {
            // If we're expanding or collapsing, ignore all touch events.
            if (mIsExpansionAnimating
                    // Also ignore events if we shouldn't be draggable.
                    || (mPositioner.showingInTaskbar() && !mIsExpanded)) {
                return;
            }

            // First, see if the magnetized object consumes the event - if so, the bubble was
            // released in the target or flung out of it, and we should ignore the event.
            if (!passEventToMagnetizedObject(ev)) {
                if (mBubbleData.isExpanded()) {
                    mExpandedAnimationController.snapBubbleBack(v, velX, velY);

                    // Re-show the expanded view if we hid it.
                    showExpandedViewIfNeeded();
                } else {
                    // Fling the stack to the edge, and save whether or not it's going to end up on
                    // the left side of the screen.
                    final boolean oldOnLeft = mStackOnLeftOrWillBe;
                    mStackOnLeftOrWillBe =
                            mStackAnimationController.flingStackThenSpringToEdge(
                                    viewInitialX + dx, velX, velY) <= 0;
                    final boolean updateForCollapsedStack = oldOnLeft != mStackOnLeftOrWillBe;
                    updateBadgesAndZOrder(updateForCollapsedStack);
                    logBubbleEvent(null /* no bubble associated with bubble stack move */,
                            FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__STACK_MOVED);
                }
                mDismissView.hide();
            }

            mIsDraggingStack = false;

            // Hide the stack after a delay, if needed.
            updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
        }
    };

    /** Click listener set on the flyout, which expands the stack when the flyout is tapped. */
    private OnClickListener mFlyoutClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            if (maybeShowStackEdu()) {
                // If we're showing user education, don't open the bubble show the education first
                mBubbleToExpandAfterFlyoutCollapse = null;
            } else {
                mBubbleToExpandAfterFlyoutCollapse = mBubbleData.getSelectedBubble();
            }

            mFlyout.removeCallbacks(mHideFlyout);
            mHideFlyout.run();
        }
    };

    /** Touch listener for the flyout. This enables the drag-to-dismiss gesture on the flyout. */
    private RelativeTouchListener mFlyoutTouchListener = new RelativeTouchListener() {

        @Override
        public boolean onDown(@NonNull View v, @NonNull MotionEvent ev) {
            mFlyout.removeCallbacks(mHideFlyout);
            return true;
        }

        @Override
        public void onMove(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy) {
            setFlyoutStateForDragLength(dx);
        }

        @Override
        public void onUp(@NonNull View v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY, float dx, float dy, float velX, float velY) {
            final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
            final boolean metRequiredVelocity =
                    onLeft ? velX < -FLYOUT_DISMISS_VELOCITY : velX > FLYOUT_DISMISS_VELOCITY;
            final boolean metRequiredDeltaX =
                    onLeft
                            ? dx < -mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS
                            : dx > mFlyout.getWidth() * FLYOUT_DRAG_PERCENT_DISMISS;
            final boolean isCancelFling = onLeft ? velX > 0 : velX < 0;
            final boolean shouldDismiss = metRequiredVelocity
                    || (metRequiredDeltaX && !isCancelFling);

            mFlyout.removeCallbacks(mHideFlyout);
            animateFlyoutCollapsed(shouldDismiss, velX);

            maybeShowStackEdu();
        }
    };

    private BubbleOverflow mBubbleOverflow;
    private StackEducationView mStackEduView;
    private ManageEducationView mManageEduView;
    private DismissView mDismissView;

    private ViewGroup mManageMenu;
    private ImageView mManageSettingsIcon;
    private TextView mManageSettingsText;
    private boolean mShowingManage = false;
    private PhysicsAnimator.SpringConfig mManageSpringConfig = new PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_LOW_BOUNCY);
    private BubblePositioner mPositioner;

    @SuppressLint("ClickableViewAccessibility")
    public BubbleStackView(Context context, BubbleController bubbleController,
            BubbleData data, @Nullable SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            ShellExecutor mainExecutor) {
        super(context);

        mDelayedAnimationExecutor = mainExecutor;
        mBubbleController = bubbleController;
        mBubbleData = data;

        Resources res = getResources();
        mMaxBubbles = res.getInteger(R.integer.bubbles_max_rendered);
        mBubbleSize = res.getDimensionPixelSize(R.dimen.individual_bubble_size);
        mBubbleElevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);
        mBubbleTouchPadding = res.getDimensionPixelSize(R.dimen.bubble_touch_padding);
        mImeOffset = res.getDimensionPixelSize(R.dimen.pip_ime_offset);

        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        int elevation = res.getDimensionPixelSize(R.dimen.bubble_elevation);

        mPositioner = mBubbleController.getPositioner();

        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[] {android.R.attr.dialogCornerRadius});
        mCornerRadius = ta.getDimensionPixelSize(0, 0);
        ta.recycle();

        final Runnable onBubbleAnimatedOut = () -> {
            if (getBubbleCount() == 0 && !mBubbleData.isShowingOverflow()) {
                mBubbleController.onAllBubblesAnimatedOut();
            }
        };

        mStackAnimationController = new StackAnimationController(
                floatingContentCoordinator, this::getBubbleCount, onBubbleAnimatedOut, mPositioner);

        mExpandedAnimationController = new ExpandedAnimationController(
                mPositioner, mExpandedViewPadding, onBubbleAnimatedOut);
        mSurfaceSynchronizer = synchronizer != null ? synchronizer : DEFAULT_SURFACE_SYNCHRONIZER;

        // Force LTR by default since most of the Bubbles UI is positioned manually by the user, or
        // is centered. It greatly simplifies translation positioning/animations. Views that will
        // actually lay out differently in RTL, such as the flyout and expanded view, will set their
        // layout direction to LOCALE.
        setLayoutDirection(LAYOUT_DIRECTION_LTR);

        mBubbleContainer = new PhysicsAnimationLayout(context);
        mBubbleContainer.setActiveController(mStackAnimationController);
        mBubbleContainer.setElevation(elevation);
        mBubbleContainer.setClipChildren(false);
        addView(mBubbleContainer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        updateUserEdu();

        mExpandedViewContainer = new FrameLayout(context);
        mExpandedViewContainer.setElevation(elevation);
        mExpandedViewContainer.setClipChildren(false);
        addView(mExpandedViewContainer);

        mAnimatingOutSurfaceContainer = new FrameLayout(getContext());
        mAnimatingOutSurfaceContainer.setLayoutParams(
                new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(mAnimatingOutSurfaceContainer);

        mAnimatingOutSurfaceView = new SurfaceView(getContext());
        mAnimatingOutSurfaceView.setUseAlpha();
        mAnimatingOutSurfaceView.setZOrderOnTop(true);
        mAnimatingOutSurfaceView.setCornerRadius(mCornerRadius);
        mAnimatingOutSurfaceView.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        mAnimatingOutSurfaceContainer.addView(mAnimatingOutSurfaceView);

        mAnimatingOutSurfaceContainer.setPadding(
                mExpandedViewContainer.getPaddingLeft(),
                mExpandedViewContainer.getPaddingTop(),
                mExpandedViewContainer.getPaddingRight(),
                mExpandedViewContainer.getPaddingBottom());

        setUpManageMenu();

        setUpFlyout();
        mFlyoutTransitionSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_LOW)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        mFlyoutTransitionSpring.addEndListener(mAfterFlyoutTransitionSpring);

        mDismissView = new DismissView(context);
        addView(mDismissView);

        final ContentResolver contentResolver = getContext().getContentResolver();
        final int dismissRadius = Settings.Secure.getInt(
                contentResolver, "bubble_dismiss_radius", mBubbleSize * 2 /* default */);

        // Save the MagneticTarget instance for the newly set up view - we'll add this to the
        // MagnetizedObjects.
        mMagneticTarget = new MagnetizedObject.MagneticTarget(
                mDismissView.getCircle(), dismissRadius);

        setClipChildren(false);
        setFocusable(true);
        mBubbleContainer.bringToFront();

        mBubbleOverflow = mBubbleData.getOverflow();
        mBubbleContainer.addView(mBubbleOverflow.getIconView(),
                mBubbleContainer.getChildCount() /* index */,
                new FrameLayout.LayoutParams(mPositioner.getBubbleSize(),
                        mPositioner.getBubbleSize()));
        updateOverflow();
        mBubbleOverflow.getIconView().setOnClickListener((View v) -> {
            mBubbleData.setShowingOverflow(true);
            mBubbleData.setSelectedBubble(mBubbleOverflow);
            mBubbleData.setExpanded(true);
        });

        mTaskbarScrim = new View(getContext());
        mTaskbarScrim.setBackgroundColor(Color.BLACK);
        addView(mTaskbarScrim);
        mTaskbarScrim.setAlpha(0f);
        mTaskbarScrim.setVisibility(GONE);

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            if (!mIsExpanded || mIsExpansionAnimating) {
                return view.onApplyWindowInsets(insets);
            }
            return view.onApplyWindowInsets(insets);
        });

        mOrientationChangedListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    mPositioner.update();
                    onDisplaySizeChanged();
                    mExpandedAnimationController.updateResources();
                    mStackAnimationController.updateResources();
                    mBubbleOverflow.updateResources();

                    if (mRelativeStackPositionBeforeRotation != null) {
                        mStackAnimationController.setStackPosition(
                                mRelativeStackPositionBeforeRotation);
                        mRelativeStackPositionBeforeRotation = null;
                    }

                    if (mIsExpanded) {
                        // Re-draw bubble row and pointer for new orientation.
                        beforeExpandedViewAnimation();
                        updateOverflowVisibility();
                        updatePointerPosition();
                        mExpandedAnimationController.expandFromStack(() -> {
                            afterExpandedViewAnimation();
                        } /* after */);
                        mExpandedViewContainer.setTranslationX(0f);
                        mExpandedViewContainer.setTranslationY(mPositioner.getExpandedViewY());
                        mExpandedViewContainer.setAlpha(1f);
                    }
                    removeOnLayoutChangeListener(mOrientationChangedListener);
                };

        final ColorMatrix animatedMatrix = new ColorMatrix();
        final ColorMatrix darkenMatrix = new ColorMatrix();

        mDesaturateAndDarkenAnimator = ValueAnimator.ofFloat(1f, 0f);
        mDesaturateAndDarkenAnimator.addUpdateListener(animation -> {
            final float animatedValue = (float) animation.getAnimatedValue();
            animatedMatrix.setSaturation(animatedValue);

            final float animatedDarkenValue = (1f - animatedValue) * DARKEN_PERCENT;
            darkenMatrix.setScale(
                    1f - animatedDarkenValue /* red */,
                    1f - animatedDarkenValue /* green */,
                    1f - animatedDarkenValue /* blue */,
                    1f /* alpha */);

            // Concat the matrices so that the animatedMatrix both desaturates and darkens.
            animatedMatrix.postConcat(darkenMatrix);

            // Update the paint and apply it to the bubble container.
            mDesaturateAndDarkenPaint.setColorFilter(new ColorMatrixColorFilter(animatedMatrix));

            if (mDesaturateAndDarkenTargetView != null) {
                mDesaturateAndDarkenTargetView.setLayerPaint(mDesaturateAndDarkenPaint);
            }
        });

        // If the stack itself is touched, it means none of its touchable views (bubbles, flyouts,
        // ActivityViews, etc.) were touched. Collapse the stack if it's expanded.
        setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                if (mShowingManage) {
                    showManageMenu(false /* show */);
                } else if (mBubbleData.isExpanded()) {
                    mBubbleData.setExpanded(false);
                }
            }

            return true;
        });

        animate()
                .setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED)
                .setDuration(FADE_IN_DURATION);

        mExpandedViewAlphaAnimator.setDuration(EXPANDED_VIEW_ALPHA_ANIMATION_DURATION);
        mExpandedViewAlphaAnimator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
        mExpandedViewAlphaAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                    // We need to be Z ordered on top in order for alpha animations to work.
                    mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(true);
                    mExpandedBubble.getExpandedView().setAlphaAnimating(true);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mExpandedBubble != null
                        && mExpandedBubble.getExpandedView() != null
                        // The surface needs to be Z ordered on top for alpha values to work on the
                        // TaskView, and if we're temporarily hidden, we are still on the screen
                        // with alpha = 0f until we animate back. Stay Z ordered on top so the alpha
                        // = 0f remains in effect.
                        && !mExpandedViewTemporarilyHidden) {
                    mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(false);
                    mExpandedBubble.getExpandedView().setAlphaAnimating(false);
                }
            }
        });
        mExpandedViewAlphaAnimator.addUpdateListener(valueAnimator -> {
            if (mExpandedBubble != null) {
                mExpandedBubble.setExpandedContentAlpha((float) valueAnimator.getAnimatedValue());
            }
        });

        mAnimatingOutSurfaceAlphaAnimator.setDuration(EXPANDED_VIEW_ALPHA_ANIMATION_DURATION);
        mAnimatingOutSurfaceAlphaAnimator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
        mAnimatingOutSurfaceAlphaAnimator.addUpdateListener(valueAnimator -> {
            if (!mExpandedViewTemporarilyHidden) {
                mAnimatingOutSurfaceView.setAlpha((float) valueAnimator.getAnimatedValue());
            }
        });
        mAnimatingOutSurfaceAlphaAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                releaseAnimatingOutBubbleBuffer();
            }
        });
    }

    /**
     * Sets whether or not the stack should become temporarily invisible by moving off the side of
     * the screen.
     *
     * If a flyout comes in while it's invisible, it will animate back in while the flyout is
     * showing but disappear again when the flyout is gone.
     */
    public void setTemporarilyInvisible(boolean invisible) {
        mTemporarilyInvisible = invisible;

        // If we are animating out, hide immediately if possible so we animate out with the status
        // bar.
        updateTemporarilyInvisibleAnimation(invisible /* hideImmediately */);
    }

    /**
     * Animates the stack to be temporarily invisible, if needed.
     *
     * If we're currently dragging the stack, or a flyout is visible, the stack will remain visible.
     * regardless of the value of {@link #mTemporarilyInvisible}. This method is called on ACTION_UP
     * as well as whenever a flyout hides, so we will animate invisible at that point if needed.
     */
    private void updateTemporarilyInvisibleAnimation(boolean hideImmediately) {
        removeCallbacks(mAnimateTemporarilyInvisibleImmediate);

        if (mIsDraggingStack) {
            // If we're dragging the stack, don't animate it invisible.
            return;
        }

        final boolean shouldHide =
                mTemporarilyInvisible && mFlyout.getVisibility() != View.VISIBLE;

        postDelayed(mAnimateTemporarilyInvisibleImmediate,
                shouldHide && !hideImmediately ? ANIMATE_TEMPORARILY_INVISIBLE_DELAY : 0);
    }

    private final Runnable mAnimateTemporarilyInvisibleImmediate = () -> {
        if (mTemporarilyInvisible && mFlyout.getVisibility() != View.VISIBLE) {
            if (mStackAnimationController.isStackOnLeftSide()) {
                animate().translationX(-mBubbleSize).start();
            } else {
                animate().translationX(mBubbleSize).start();
            }
        } else {
            animate().translationX(0).start();
        }
    };

    // TODO: Create ManageMenuView and move setup / animations there
    private void setUpManageMenu() {
        if (mManageMenu != null) {
            removeView(mManageMenu);
        }

        mManageMenu = (ViewGroup) LayoutInflater.from(getContext()).inflate(
                R.layout.bubble_manage_menu, this, false);
        mManageMenu.setVisibility(View.INVISIBLE);

        PhysicsAnimator.getInstance(mManageMenu).setDefaultSpringConfig(mManageSpringConfig);

        mManageMenu.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mManageMenu.setClipToOutline(true);

        mManageMenu.findViewById(R.id.bubble_manage_menu_dismiss_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    dismissBubbleIfExists(mBubbleData.getSelectedBubble());
                });

        mManageMenu.findViewById(R.id.bubble_manage_menu_dont_bubble_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    mUnbubbleConversationCallback.accept(mBubbleData.getSelectedBubble().getKey());
                });

        mManageMenu.findViewById(R.id.bubble_manage_menu_settings_container).setOnClickListener(
                view -> {
                    showManageMenu(false /* show */);
                    final BubbleViewProvider bubble = mBubbleData.getSelectedBubble();
                    if (bubble != null && mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                        // If it's in the stack it's a proper Bubble.
                        final Intent intent = ((Bubble) bubble).getSettingsIntent(mContext);
                        mBubbleData.setExpanded(false);
                        mContext.startActivityAsUser(intent, ((Bubble) bubble).getUser());
                        logBubbleEvent(bubble,
                                FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
                    }
                });

        mManageSettingsIcon = mManageMenu.findViewById(R.id.bubble_manage_menu_settings_icon);
        mManageSettingsText = mManageMenu.findViewById(R.id.bubble_manage_menu_settings_name);

        // The menu itself should respect locale direction so the icons are on the correct side.
        mManageMenu.setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
        addView(mManageMenu);
    }

    /**
     * Whether the educational view should show for the expanded view "manage" menu.
     */
    private boolean shouldShowManageEdu() {
        final boolean seen = getPrefBoolean(ManageEducationViewKt.PREF_MANAGED_EDUCATION);
        final boolean shouldShow = (!seen || BubbleDebugConfig.forceShowUserEducation(mContext))
                && mExpandedBubble != null;
        if (BubbleDebugConfig.DEBUG_USER_EDUCATION) {
            Log.d(TAG, "Show manage edu: " + shouldShow);
        }
        return shouldShow;
    }

    private void maybeShowManageEdu() {
        if (!shouldShowManageEdu()) {
            return;
        }
        if (mManageEduView == null) {
            mManageEduView = new ManageEducationView(mContext);
            addView(mManageEduView);
        }
        mManageEduView.show(mExpandedBubble.getExpandedView(), mTempRect);
    }

    /**
     * Whether education view should show for the collapsed stack.
     */
    private boolean shouldShowStackEdu() {
        final boolean seen = getPrefBoolean(StackEducationViewKt.PREF_STACK_EDUCATION);
        final boolean shouldShow = !seen || BubbleDebugConfig.forceShowUserEducation(mContext);
        if (BubbleDebugConfig.DEBUG_USER_EDUCATION) {
            Log.d(TAG, "Show stack edu: " + shouldShow);
        }
        return shouldShow;
    }

    private boolean getPrefBoolean(String key) {
        return mContext.getSharedPreferences(mContext.getPackageName(), Context.MODE_PRIVATE)
                .getBoolean(key, false /* default */);
    }

    /**
     * @return true if education view for collapsed stack should show and was not showing before.
     */
    private boolean maybeShowStackEdu() {
        if (!shouldShowStackEdu()) {
            return false;
        }
        if (mStackEduView == null) {
            mStackEduView = new StackEducationView(mContext);
            addView(mStackEduView);
        }
        return mStackEduView.show(mPositioner.getDefaultStartPosition());
    }

    private void updateUserEdu() {
        maybeShowStackEdu();
        if (mManageEduView != null) {
            mManageEduView.invalidate();
        }
        maybeShowManageEdu();
        if (mStackEduView != null) {
            mStackEduView.invalidate();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setUpFlyout() {
        if (mFlyout != null) {
            removeView(mFlyout);
        }
        mFlyout = new BubbleFlyoutView(getContext());
        mFlyout.setVisibility(GONE);
        mFlyout.setOnClickListener(mFlyoutClickListener);
        mFlyout.setOnTouchListener(mFlyoutTouchListener);
        addView(mFlyout, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }

    void updateFontScale() {
        setUpManageMenu();
        mFlyout.updateFontSize();
        for (Bubble b : mBubbleData.getBubbles()) {
            if (b.getExpandedView() != null) {
                b.getExpandedView().updateFontSize();
            }
        }
        if (mBubbleOverflow != null) {
            mBubbleOverflow.getExpandedView().updateFontSize();
        }
    }

    private void updateOverflow() {
        mBubbleOverflow.update();
        mBubbleContainer.reorderView(mBubbleOverflow.getIconView(),
                mBubbleContainer.getChildCount() - 1 /* index */);
        updateOverflowVisibility();
    }

    void updateOverflowButtonDot() {
        for (Bubble b : mBubbleData.getOverflowBubbles()) {
            if (b.showDot()) {
                mBubbleOverflow.setShowDot(true);
                return;
            }
        }
        mBubbleOverflow.setShowDot(false);
    }

    /**
     * Handle theme changes.
     */
    public void onThemeChanged() {
        setUpFlyout();
        setUpManageMenu();
        updateOverflow();
        updateUserEdu();
        updateExpandedViewTheme();
    }

    /**
     * Respond to the phone being rotated by repositioning the stack and hiding any flyouts.
     * This is called prior to the rotation occurring, any values that should be updated
     * based on the new rotation should occur in {@link #mOrientationChangedListener}.
     */
    public void onOrientationChanged() {
        mRelativeStackPositionBeforeRotation = new RelativeStackPosition(
                mPositioner.getRestingPosition(),
                mStackAnimationController.getAllowableStackPositionRegion());
        mManageMenu.setVisibility(View.INVISIBLE);
        mShowingManage = false;

        addOnLayoutChangeListener(mOrientationChangedListener);
        hideFlyoutImmediate();
    }

    /** Tells the views with locale-dependent layout direction to resolve the new direction. */
    public void onLayoutDirectionChanged(int direction) {
        mManageMenu.setLayoutDirection(direction);
        mFlyout.setLayoutDirection(direction);
        if (mStackEduView != null) {
            mStackEduView.setLayoutDirection(direction);
        }
        if (mManageEduView != null) {
            mManageEduView.setLayoutDirection(direction);
        }
        updateExpandedViewDirection(direction);
    }

    /** Respond to the display size change by recalculating view size and location. */
    public void onDisplaySizeChanged() {
        updateOverflow();
        setUpManageMenu();
        setUpFlyout();
        mBubbleSize = mPositioner.getBubbleSize();
        for (Bubble b : mBubbleData.getBubbles()) {
            if (b.getIconView() == null) {
                Log.d(TAG, "Display size changed. Icon null: " + b);
                continue;
            }
            b.getIconView().setLayoutParams(new LayoutParams(mBubbleSize, mBubbleSize));
            if (b.getExpandedView() != null) {
                b.getExpandedView().updateDimensions();
            }
        }
        mBubbleOverflow.getIconView().setLayoutParams(new LayoutParams(mBubbleSize, mBubbleSize));
        mExpandedAnimationController.updateResources();
        mStackAnimationController.updateResources();
        mDismissView.updateResources();
        mMagneticTarget.setMagneticFieldRadiusPx(mBubbleSize * 2);
        mStackAnimationController.setStackPosition(
                new RelativeStackPosition(
                        mPositioner.getRestingPosition(),
                        mStackAnimationController.getAllowableStackPositionRegion()));
        if (mIsExpanded) {
            updateExpandedView();
        }
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

        mTempRect.setEmpty();
        getTouchableRegion(mTempRect);
        inoutInfo.touchableRegion.set(mTempRect);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        getViewTreeObserver().addOnDrawListener(mSystemGestureExcludeUpdater);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(mViewUpdater);
        getViewTreeObserver().removeOnDrawListener(mSystemGestureExcludeUpdater);
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        if (mBubbleOverflow != null) {
            mBubbleOverflow.cleanUpExpandedState();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        setupLocalMenu(info);
    }

    void updateExpandedViewTheme() {
        final List<Bubble> bubbles = mBubbleData.getBubbles();
        if (bubbles.isEmpty()) {
            return;
        }
        bubbles.forEach(bubble -> {
            if (bubble.getExpandedView() != null) {
                bubble.getExpandedView().applyThemeAttrs();
            }
        });
    }

    void updateExpandedViewDirection(int direction) {
        final List<Bubble> bubbles = mBubbleData.getBubbles();
        if (bubbles.isEmpty()) {
            return;
        }
        bubbles.forEach(bubble -> {
            if (bubble.getExpandedView() != null) {
                bubble.getExpandedView().setLayoutDirection(direction);
            }
        });
    }

    void setupLocalMenu(AccessibilityNodeInfo info) {
        Resources res = mContext.getResources();

        // Custom local actions.
        AccessibilityAction moveTopLeft = new AccessibilityAction(R.id.action_move_top_left,
                res.getString(R.string.bubble_accessibility_action_move_top_left));
        info.addAction(moveTopLeft);

        AccessibilityAction moveTopRight = new AccessibilityAction(R.id.action_move_top_right,
                res.getString(R.string.bubble_accessibility_action_move_top_right));
        info.addAction(moveTopRight);

        AccessibilityAction moveBottomLeft = new AccessibilityAction(R.id.action_move_bottom_left,
                res.getString(R.string.bubble_accessibility_action_move_bottom_left));
        info.addAction(moveBottomLeft);

        AccessibilityAction moveBottomRight = new AccessibilityAction(R.id.action_move_bottom_right,
                res.getString(R.string.bubble_accessibility_action_move_bottom_right));
        info.addAction(moveBottomRight);

        // Default actions.
        info.addAction(AccessibilityAction.ACTION_DISMISS);
        if (mIsExpanded) {
            info.addAction(AccessibilityAction.ACTION_COLLAPSE);
        } else {
            info.addAction(AccessibilityAction.ACTION_EXPAND);
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        final RectF stackBounds = mStackAnimationController.getAllowableStackPositionRegion();

        // R constants are not final so we cannot use switch-case here.
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            mBubbleData.dismissAll(Bubbles.DISMISS_ACCESSIBILITY_ACTION);
            announceForAccessibility(
                    getResources().getString(R.string.accessibility_bubble_dismissed));
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            mBubbleData.setExpanded(false);
            return true;
        } else if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            mBubbleData.setExpanded(true);
            return true;
        } else if (action == R.id.action_move_top_left) {
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_top_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.top);
            return true;
        } else if (action == R.id.action_move_bottom_left) {
            mStackAnimationController.springStackAfterFling(stackBounds.left, stackBounds.bottom);
            return true;
        } else if (action == R.id.action_move_bottom_right) {
            mStackAnimationController.springStackAfterFling(stackBounds.right, stackBounds.bottom);
            return true;
        }
        return false;
    }

    /**
     * Update content description for a11y TalkBack.
     */
    public void updateContentDescription() {
        if (mBubbleData.getBubbles().isEmpty()) {
            return;
        }

        for (int i = 0; i < mBubbleData.getBubbles().size(); i++) {
            final Bubble bubble = mBubbleData.getBubbles().get(i);
            final String appName = bubble.getAppName();

            String titleStr = bubble.getTitle();
            if (titleStr == null) {
                titleStr = getResources().getString(R.string.notification_bubble_title);
            }

            if (bubble.getIconView() != null) {
                if (mIsExpanded || i > 0) {
                    bubble.getIconView().setContentDescription(getResources().getString(
                            R.string.bubble_content_description_single, titleStr, appName));
                } else {
                    final int moreCount = mBubbleContainer.getChildCount() - 1;
                    bubble.getIconView().setContentDescription(getResources().getString(
                            R.string.bubble_content_description_stack,
                            titleStr, appName, moreCount));
                }
            }
        }
    }

    private void updateSystemGestureExcludeRects() {
        // Exclude the region occupied by the first BubbleView in the stack
        Rect excludeZone = mSystemGestureExclusionRects.get(0);
        if (getBubbleCount() > 0) {
            View firstBubble = mBubbleContainer.getChildAt(0);
            excludeZone.set(firstBubble.getLeft(), firstBubble.getTop(), firstBubble.getRight(),
                    firstBubble.getBottom());
            excludeZone.offset((int) (firstBubble.getTranslationX() + 0.5f),
                    (int) (firstBubble.getTranslationY() + 0.5f));
            mBubbleContainer.setSystemGestureExclusionRects(mSystemGestureExclusionRects);
        } else {
            excludeZone.setEmpty();
            mBubbleContainer.setSystemGestureExclusionRects(Collections.emptyList());
        }
    }

    /**
     * Sets the listener to notify when the bubble stack is expanded.
     */
    public void setExpandListener(Bubbles.BubbleExpandListener listener) {
        mExpandListener = listener;
    }

    /** Sets the function to call to un-bubble the given conversation. */
    public void setUnbubbleConversationCallback(
            Consumer<String> unbubbleConversationCallback) {
        mUnbubbleConversationCallback = unbubbleConversationCallback;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    /**
     * Whether the stack of bubbles is animating to or from expansion.
     */
    public boolean isExpansionAnimating() {
        return mIsExpansionAnimating;
    }

    /**
     * The {@link Bubble} that is expanded, null if one does not exist.
     */
    @VisibleForTesting
    @Nullable
    public BubbleViewProvider getExpandedBubble() {
        return mExpandedBubble;
    }

    // via BubbleData.Listener
    @SuppressLint("ClickableViewAccessibility")
    void addBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "addBubble: " + bubble);
        }

        if (getBubbleCount() == 0 && shouldShowStackEdu()) {
            // Override the default stack position if we're showing user education.
            mStackAnimationController.setStackPosition(mPositioner.getDefaultStartPosition());
        }

        if (getBubbleCount() == 0) {
            mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
        }

        if (bubble.getIconView() == null) {
            return;
        }

        // Set the dot position to the opposite of the side the stack is resting on, since the stack
        // resting slightly off-screen would result in the dot also being off-screen.
        bubble.getIconView().setDotBadgeOnLeft(!mStackOnLeftOrWillBe /* onLeft */);

        bubble.getIconView().setOnClickListener(mBubbleClickListener);
        bubble.getIconView().setOnTouchListener(mBubbleTouchListener);

        mBubbleContainer.addView(bubble.getIconView(), 0,
                new FrameLayout.LayoutParams(mPositioner.getBubbleSize(),
                        mPositioner.getBubbleSize()));
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__POSTED);
    }

    // via BubbleData.Listener
    void removeBubble(Bubble bubble) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "removeBubble: " + bubble);
        }
        // Remove it from the views
        for (int i = 0; i < getBubbleCount(); i++) {
            View v = mBubbleContainer.getChildAt(i);
            if (v instanceof BadgedImageView
                    && ((BadgedImageView) v).getKey().equals(bubble.getKey())) {
                mBubbleContainer.removeViewAt(i);
                if (mBubbleData.hasOverflowBubbleWithKey(bubble.getKey())) {
                    bubble.cleanupExpandedView();
                } else {
                    bubble.cleanupViews();
                }
                updatePointerPosition();
                logBubbleEvent(bubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__DISMISSED);
                return;
            }
        }
        Log.d(TAG, "was asked to remove Bubble, but didn't find the view! " + bubble);
    }

    private void updateOverflowVisibility() {
        mBubbleOverflow.setVisible((mIsExpanded || mBubbleData.isShowingOverflow())
                ? VISIBLE
                : GONE);
    }

    // via BubbleData.Listener
    void updateBubble(Bubble bubble) {
        animateInFlyoutForBubble(bubble);
        requestUpdate();
        logBubbleEvent(bubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__UPDATED);
    }

    /**
     * Update bubble order and pointer position.
     */
    public void updateBubbleOrder(List<Bubble> bubbles) {
        final Runnable reorder = () -> {
            for (int i = 0; i < bubbles.size(); i++) {
                Bubble bubble = bubbles.get(i);
                mBubbleContainer.reorderView(bubble.getIconView(), i);
            }
        };
        if (mIsExpanded || isExpansionAnimating()) {
            reorder.run();
            updateBadgesAndZOrder(false /* setBadgeForCollapsedStack */);
        } else if (!isExpansionAnimating()) {
            List<View> bubbleViews = bubbles.stream()
                    .map(b -> b.getIconView()).collect(Collectors.toList());
            mStackAnimationController.animateReorder(bubbleViews, reorder);
        }
        updatePointerPosition();
    }

    /**
     * Changes the currently selected bubble. If the stack is already expanded, the newly selected
     * bubble will be shown immediately. This does not change the expanded state or change the
     * position of any bubble.
     */
    // via BubbleData.Listener
    public void setSelectedBubble(@Nullable BubbleViewProvider bubbleToSelect) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setSelectedBubble: " + bubbleToSelect);
        }

        if (bubbleToSelect == null) {
            mBubbleData.setShowingOverflow(false);
            return;
        }

        // Ignore this new bubble only if it is the exact same bubble object. Otherwise, we'll want
        // to re-render it even if it has the same key (equals() returns true). If the currently
        // expanded bubble is removed and instantly re-added, we'll get back a new Bubble instance
        // with the same key (with newly inflated expanded views), and we need to render those new
        // views.
        if (mExpandedBubble == bubbleToSelect) {
            return;
        }

        if (bubbleToSelect.getKey().equals(BubbleOverflow.KEY)) {
            mBubbleData.setShowingOverflow(true);
        } else {
            mBubbleData.setShowingOverflow(false);
        }

        if (mIsExpanded && mIsExpansionAnimating) {
            // If the bubble selection changed during the expansion animation, the expanding bubble
            // probably crashed or immediately removed itself (or, we just got unlucky with a new
            // auto-expanding bubble showing up at just the right time). Cancel the animations so we
            // can start fresh.
            cancelAllExpandCollapseSwitchAnimations();
        }
        showManageMenu(false /* show */);

        // If we're expanded, screenshot the currently expanded bubble (before expanding the newly
        // selected bubble) so we can animate it out.
        if (mIsExpanded && mExpandedBubble != null && mExpandedBubble.getExpandedView() != null
                && !mExpandedViewTemporarilyHidden) {
            if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                // Before screenshotting, have the real ActivityView show on top of other surfaces
                // so that the screenshot doesn't flicker on top of it.
                mExpandedBubble.getExpandedView().setSurfaceZOrderedOnTop(true);
            }

            try {
                screenshotAnimatingOutBubbleIntoSurface((success) -> {
                    mAnimatingOutSurfaceContainer.setVisibility(
                            success ? View.VISIBLE : View.INVISIBLE);
                    showNewlySelectedBubble(bubbleToSelect);
                });
            } catch (Exception e) {
                showNewlySelectedBubble(bubbleToSelect);
                e.printStackTrace();
            }
        } else {
            showNewlySelectedBubble(bubbleToSelect);
        }
    }

    private void showNewlySelectedBubble(BubbleViewProvider bubbleToSelect) {
        final BubbleViewProvider previouslySelected = mExpandedBubble;
        mExpandedBubble = bubbleToSelect;
        updatePointerPosition();

        if (mIsExpanded) {
            hideCurrentInputMethod();

            // Make the container of the expanded view transparent before removing the expanded view
            // from it. Otherwise a punch hole created by {@link android.view.SurfaceView} in the
            // expanded view becomes visible on the screen. See b/126856255
            mExpandedViewContainer.setAlpha(0.0f);
            mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                if (previouslySelected != null) {
                    previouslySelected.setTaskViewVisibility(false);
                }

                updateExpandedBubble();
                requestUpdate();

                logBubbleEvent(previouslySelected,
                        FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
                logBubbleEvent(bubbleToSelect,
                        FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
                notifyExpansionChanged(previouslySelected, false /* expanded */);
                notifyExpansionChanged(bubbleToSelect, true /* expanded */);
            });
        }
    }

    /**
     * Changes the expanded state of the stack.
     *
     * @param shouldExpand whether the bubble stack should appear expanded
     */
    // via BubbleData.Listener
    public void setExpanded(boolean shouldExpand) {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "setExpanded: " + shouldExpand);
        }

        if (!shouldExpand) {
            // If we're collapsing, release the animating-out surface immediately since we have no
            // need for it, and this ensures it cannot remain visible as we collapse.
            releaseAnimatingOutBubbleBuffer();
        }

        if (shouldExpand == mIsExpanded) {
            return;
        }

        hideCurrentInputMethod();

        mBubbleController.getSysuiProxy().onStackExpandChanged(shouldExpand);

        if (mIsExpanded) {
            animateCollapse();
            logBubbleEvent(mExpandedBubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__COLLAPSED);
        } else {
            animateExpansion();
            // TODO: move next line to BubbleData
            logBubbleEvent(mExpandedBubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED);
            logBubbleEvent(mExpandedBubble,
                    FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__STACK_EXPANDED);
        }
        notifyExpansionChanged(mExpandedBubble, mIsExpanded);
    }

    void setBubbleVisibility(Bubble b, boolean visible) {
        if (b.getIconView() != null) {
            b.getIconView().setVisibility(visible ? VISIBLE : GONE);
        }
        // TODO(b/181166384): Animate in / out & handle adjusting how the bubbles overlap
    }

    /**
     * Asks the BubbleController to hide the IME from anywhere, whether it's focused on Bubbles or
     * not.
     */
    void hideCurrentInputMethod() {
        mBubbleController.hideCurrentInputMethod();
    }

    /** Set the stack position to whatever the positioner says. */
    void updateStackPosition() {
        mStackAnimationController.setStackPosition(mPositioner.getRestingPosition());
        mDismissView.hide();
    }

    private void beforeExpandedViewAnimation() {
        mIsExpansionAnimating = true;
        hideFlyoutImmediate();
        updateExpandedBubble();
        updateExpandedView();
    }

    private void afterExpandedViewAnimation() {
        mIsExpansionAnimating = false;
        updateExpandedView();
        requestUpdate();
    }

    /** Animate the expanded view hidden. This is done while we're dragging out a bubble. */
    private void hideExpandedViewIfNeeded() {
        if (mExpandedViewTemporarilyHidden
                || mExpandedBubble == null
                || mExpandedBubble.getExpandedView() == null) {
            return;
        }

        mExpandedViewTemporarilyHidden = true;

        // Scale down.
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .addUpdateListener((target, values) ->
                        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix))
                .start();

        // Animate alpha from 1f to 0f.
        mExpandedViewAlphaAnimator.reverse();
    }

    /**
     * Animate the expanded view visible again. This is done when we're done dragging out a bubble.
     */
    private void showExpandedViewIfNeeded() {
        if (!mExpandedViewTemporarilyHidden) {
            return;
        }

        mExpandedViewTemporarilyHidden = false;

        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                        mScaleOutSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                        mScaleOutSpringConfig)
                .addUpdateListener((target, values) ->
                        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix))
                .start();

        mExpandedViewAlphaAnimator.start();
    }

    private void animateExpansion() {
        cancelDelayedExpandCollapseSwitchAnimations();
        final boolean showVertically = mPositioner.showBubblesVertically();
        mIsExpanded = true;
        if (mStackEduView != null) {
            mStackEduView.hide(true /* fromExpansion */);
        }
        beforeExpandedViewAnimation();

        updateBadgesAndZOrder(false /* setBadgeForCollapsedStack */);
        mBubbleContainer.setActiveController(mExpandedAnimationController);
        updateOverflowVisibility();
        updatePointerPosition();
        mExpandedAnimationController.expandFromStack(() -> {
            if (mIsExpanded && mExpandedBubble.getExpandedView() != null) {
                maybeShowManageEdu();
            }
        } /* after */);

        if (mPositioner.showingInTaskbar()
                // Don't need the scrim when the bar is at the bottom
                && mPositioner.getTaskbarPosition() != BubblePositioner.TASKBAR_POSITION_BOTTOM) {
            mTaskbarScrim.getLayoutParams().width = mPositioner.getTaskbarSize();
            mTaskbarScrim.setTranslationX(mStackOnLeftOrWillBe
                    ? 0f
                    : mPositioner.getAvailableRect().right - mPositioner.getTaskbarSize());
            mTaskbarScrim.setVisibility(VISIBLE);
            mTaskbarScrim.animate().alpha(1f).start();
        }

        mExpandedViewContainer.setTranslationX(0f);
        mExpandedViewContainer.setTranslationY(mPositioner.getExpandedViewY());
        mExpandedViewContainer.setAlpha(1f);

        int index;
        if (mExpandedBubble != null && BubbleOverflow.KEY.equals(mExpandedBubble.getKey())) {
            index = mBubbleData.getBubbles().size();
        } else {
            index = getBubbleIndex(mExpandedBubble);
        }
        // Position of the bubble we're expanding, once it's settled in its row.
        final float bubbleWillBeAt =
                mExpandedAnimationController.getBubbleXOrYForOrientation(index);

        // How far horizontally the bubble will be animating. We'll wait a bit longer for bubbles
        // that are animating farther, so that the expanded view doesn't move as much.
        final float relevantStackPosition = showVertically
                ? mStackAnimationController.getStackPosition().y
                : mStackAnimationController.getStackPosition().x;
        final float distanceAnimated = Math.abs(bubbleWillBeAt - relevantStackPosition);

        // Wait for the path animation target to reach its end, and add a small amount of extra time
        // if the bubble is moving a lot horizontally.
        long startDelay = 0L;

        // Should not happen since we lay out before expanding, but just in case...
        if (getWidth() > 0) {
            startDelay = (long)
                    (ExpandedAnimationController.EXPAND_COLLAPSE_TARGET_ANIM_DURATION * 1.2f
                            + (distanceAnimated / getWidth()) * 30);
        }

        // Set the pivot point for the scale, so the expanded view animates out from the bubble.
        if (showVertically) {
            float pivotX;
            float pivotY = bubbleWillBeAt + mBubbleSize / 2f;
            if (mStackOnLeftOrWillBe) {
                pivotX = mPositioner.getAvailableRect().left + mBubbleSize + mExpandedViewPadding;
            } else {
                pivotX = mPositioner.getAvailableRect().right - mBubbleSize - mExpandedViewPadding;
            }
            mExpandedViewContainerMatrix.setScale(
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    pivotX, pivotY);
        } else {
            mExpandedViewContainerMatrix.setScale(
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    bubbleWillBeAt + mBubbleSize / 2f,
                    mPositioner.getExpandedViewY());
        }
        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);

        if (mExpandedBubble.getExpandedView() != null) {
            mExpandedBubble.setExpandedContentAlpha(0f);

            // We'll be starting the alpha animation after a slight delay, so set this flag early
            // here.
            mExpandedBubble.getExpandedView().setAlphaAnimating(true);
        }

        mDelayedAnimation = () -> {
            mExpandedViewAlphaAnimator.start();

            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                    .spring(AnimatableScaleMatrix.SCALE_X,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .spring(AnimatableScaleMatrix.SCALE_Y,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .addUpdateListener((target, values) -> {
                        if (mExpandedBubble == null || mExpandedBubble.getIconView() == null) {
                            return;
                        }
                        float translation = showVertically
                                ? mExpandedBubble.getIconView().getTranslationY()
                                : mExpandedBubble.getIconView().getTranslationX();
                        mExpandedViewContainerMatrix.postTranslate(
                                translation - bubbleWillBeAt,
                                0);
                        mExpandedViewContainer.setAnimationMatrix(
                                mExpandedViewContainerMatrix);
                    })
                    .withEndActions(() -> {
                        afterExpandedViewAnimation();
                        if (mExpandedBubble != null
                                && mExpandedBubble.getExpandedView() != null) {
                            mExpandedBubble.getExpandedView()
                                    .setSurfaceZOrderedOnTop(false);
                        }
                    })
                    .start();
        };
        mDelayedAnimationExecutor.executeDelayed(mDelayedAnimation, startDelay);
    }

    private void animateCollapse() {
        cancelDelayedExpandCollapseSwitchAnimations();

        // Hide the menu if it's visible.
        showManageMenu(false);

        mIsExpanded = false;
        mIsExpansionAnimating = true;

        mBubbleContainer.cancelAllAnimations();

        // If we were in the middle of swapping, the animating-out surface would have been scaling
        // to zero - finish it off.
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();
        mAnimatingOutSurfaceContainer.setScaleX(0f);
        mAnimatingOutSurfaceContainer.setScaleY(0f);

        // Let the expanded animation controller know that it shouldn't animate child adds/reorders
        // since we're about to animate collapsed.
        mExpandedAnimationController.notifyPreparingToCollapse();

        mExpandedAnimationController.collapseBackToStack(
                mStackAnimationController.getStackPositionAlongNearestHorizontalEdge()
                /* collapseTo */,
                () -> mBubbleContainer.setActiveController(mStackAnimationController));

        if (mTaskbarScrim.getVisibility() == VISIBLE) {
            mTaskbarScrim.animate().alpha(0f).start();
        }

        int index;
        if (mExpandedBubble != null && BubbleOverflow.KEY.equals(mExpandedBubble.getKey())) {
            index = mBubbleData.getBubbles().size();
        } else {
            index = mBubbleData.getBubbles().indexOf(mExpandedBubble);
        }
        // Value the bubble is animating from (back into the stack).
        final float expandingFromBubbleAt =
                mExpandedAnimationController.getBubbleXOrYForOrientation(index);
        final boolean showVertically = mPositioner.showBubblesVertically();
        if (mPositioner.showBubblesVertically()) {
            float pivotX;
            float pivotY = expandingFromBubbleAt + mBubbleSize / 2f;
            if (mStackOnLeftOrWillBe) {
                pivotX = mPositioner.getAvailableRect().left + mBubbleSize + mExpandedViewPadding;
            } else {
                pivotX = mPositioner.getAvailableRect().right - mBubbleSize - mExpandedViewPadding;
            }
            mExpandedViewContainerMatrix.setScale(
                    1f, 1f,
                    pivotX, pivotY);
        } else {
            mExpandedViewContainerMatrix.setScale(
                    1f, 1f,
                    expandingFromBubbleAt + mBubbleSize / 2f,
                    mPositioner.getExpandedViewY());
        }

        mExpandedViewAlphaAnimator.reverse();

        // When the animation completes, we should no longer be showing the content.
        if (mExpandedBubble.getExpandedView() != null) {
            mExpandedBubble.getExpandedView().setContentVisibility(false);
        }

        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .addUpdateListener((target, values) -> {
                    mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);
                })
                .withEndActions(() -> {
                    final BubbleViewProvider previouslySelected = mExpandedBubble;
                    beforeExpandedViewAnimation();
                    if (mManageEduView != null) {
                        mManageEduView.hide(false /* fromExpansion */);
                    }

                    if (DEBUG_BUBBLE_STACK_VIEW) {
                        Log.d(TAG, "animateCollapse");
                        Log.d(TAG, BubbleDebugConfig.formatBubblesString(getBubblesOnScreen(),
                                mExpandedBubble));
                    }
                    updateOverflowVisibility();
                    updateBadgesAndZOrder(true /* setBadgeForCollapsedStack */);
                    afterExpandedViewAnimation();
                    if (previouslySelected != null) {
                        previouslySelected.setTaskViewVisibility(false);
                    }

                    if (mPositioner.showingInTaskbar()) {
                        mTaskbarScrim.setVisibility(GONE);
                    }
                })
                .start();
    }

    private void animateSwitchBubbles() {
        // If we're no longer expanded, this is meaningless.
        if (!mIsExpanded) {
            return;
        }

        mIsBubbleSwitchAnimating = true;

        // The surface contains a screenshot of the animating out bubble, so we just need to animate
        // it out (and then release the GraphicBuffer).
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();

        mAnimatingOutSurfaceAlphaAnimator.reverse();
        mExpandedViewAlphaAnimator.start();

        if (mPositioner.showBubblesVertically()) {
            float translationX = mStackAnimationController.isStackOnLeftSide()
                    ? mAnimatingOutSurfaceContainer.getTranslationX() + mBubbleSize * 2
                    : mAnimatingOutSurfaceContainer.getTranslationX();
            PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer)
                    .spring(DynamicAnimation.TRANSLATION_X, translationX, mTranslateSpringConfig)
                    .start();
        } else {
            PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer)
                    .spring(DynamicAnimation.TRANSLATION_Y,
                            mAnimatingOutSurfaceContainer.getTranslationY() - mBubbleSize,
                    mTranslateSpringConfig)
                    .start();
        }

        boolean isOverflow = mExpandedBubble != null
                && mExpandedBubble.getKey().equals(BubbleOverflow.KEY);
        float expandingFromBubbleDestination =
                mExpandedAnimationController.getBubbleXOrYForOrientation(isOverflow
                        ? getBubbleCount()
                        : mBubbleData.getBubbles().indexOf(mExpandedBubble));

        mExpandedViewContainer.setAlpha(1f);
        mExpandedViewContainer.setVisibility(View.VISIBLE);

        if (mPositioner.showBubblesVertically()) {
            float pivotX;
            float pivotY = expandingFromBubbleDestination + mBubbleSize / 2f;
            if (mStackOnLeftOrWillBe) {
                pivotX = mPositioner.getAvailableRect().left + mBubbleSize + mExpandedViewPadding;
            } else {
                pivotX = mPositioner.getAvailableRect().right - mBubbleSize - mExpandedViewPadding;

            }
            mExpandedViewContainerMatrix.setScale(
                    0f, 0f,
                    pivotX, pivotY);
        } else {
            mExpandedViewContainerMatrix.setScale(
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                    expandingFromBubbleDestination + mBubbleSize / 2f,
                    mPositioner.getExpandedViewY());
        }

        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);

        mDelayedAnimationExecutor.executeDelayed(() -> {
            if (!mIsExpanded) {
                mIsBubbleSwitchAnimating = false;
                return;
            }

            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
            PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                    .spring(AnimatableScaleMatrix.SCALE_X,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .spring(AnimatableScaleMatrix.SCALE_Y,
                            AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                            mScaleInSpringConfig)
                    .addUpdateListener((target, values) -> {
                        mExpandedViewContainer.setAnimationMatrix(mExpandedViewContainerMatrix);
                    })
                    .withEndActions(() -> {
                        mExpandedViewTemporarilyHidden = false;
                        mIsBubbleSwitchAnimating = false;
                    })
                    .start();
        }, 25);
    }

    /**
     * Cancels any delayed steps for expand/collapse and bubble switch animations, and resets the is
     * animating flags for those animations.
     */
    private void cancelDelayedExpandCollapseSwitchAnimations() {
        mDelayedAnimationExecutor.removeCallbacks(mDelayedAnimation);

        mIsExpansionAnimating = false;
        mIsBubbleSwitchAnimating = false;
    }

    private void cancelAllExpandCollapseSwitchAnimations() {
        cancelDelayedExpandCollapseSwitchAnimations();

        PhysicsAnimator.getInstance(mAnimatingOutSurfaceView).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();

        mExpandedViewContainer.setAnimationMatrix(null);
    }

    private void notifyExpansionChanged(BubbleViewProvider bubble, boolean expanded) {
        if (mExpandListener != null && bubble != null) {
            mExpandListener.onBubbleExpandChanged(expanded, bubble.getKey());
        }
    }

    /** Moves the bubbles out of the way if they're going to be over the keyboard. */
    public void onImeVisibilityChanged(boolean visible, int height) {
        mStackAnimationController.setImeHeight(visible ? height + mImeOffset : 0);

        if (!mIsExpanded && getBubbleCount() > 0) {
            final float stackDestinationY =
                    mStackAnimationController.animateForImeVisibility(visible);

            // How far the stack is animating due to IME, we'll just animate the flyout by that
            // much too.
            final float stackDy =
                    stackDestinationY - mStackAnimationController.getStackPosition().y;

            // If the flyout is visible, translate it along with the bubble stack.
            if (mFlyout.getVisibility() == VISIBLE) {
                PhysicsAnimator.getInstance(mFlyout)
                        .spring(DynamicAnimation.TRANSLATION_Y,
                                mFlyout.getTranslationY() + stackDy,
                                FLYOUT_IME_ANIMATION_SPRING_CONFIG)
                        .start();
            }
        } else if (mIsExpanded && mExpandedBubble != null
                && mExpandedBubble.getExpandedView() != null) {
            mExpandedBubble.getExpandedView().setImeVisible(visible);
        }
    }

    /**
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN && ev.getActionIndex() != mPointerIndexDown) {
            // Ignore touches from additional pointer indices.
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mPointerIndexDown = ev.getActionIndex();
        } else if (ev.getAction() == MotionEvent.ACTION_UP
                || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            mPointerIndexDown = -1;
        }

        boolean dispatched = super.dispatchTouchEvent(ev);

        // If a new bubble arrives while the collapsed stack is being dragged, it will be positioned
        // at the front of the stack (under the touch position). Subsequent ACTION_MOVE events will
        // then be passed to the new bubble, which will not consume them since it hasn't received an
        // ACTION_DOWN yet. Work around this by passing MotionEvents directly to the touch handler
        // until the current gesture ends with an ACTION_UP event.
        if (!dispatched && !mIsExpanded && mIsGestureInProgress) {
            dispatched = mBubbleTouchListener.onTouch(this /* view */, ev);
        }

        mIsGestureInProgress =
                ev.getAction() != MotionEvent.ACTION_UP
                        && ev.getAction() != MotionEvent.ACTION_CANCEL;

        return dispatched;
    }

    void setFlyoutStateForDragLength(float deltaX) {
        // This shouldn't happen, but if it does, just wait until the flyout lays out. This method
        // is continually called.
        if (mFlyout.getWidth() <= 0) {
            return;
        }

        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        mFlyoutDragDeltaX = deltaX;

        final float collapsePercent =
                onLeft ? -deltaX / mFlyout.getWidth() : deltaX / mFlyout.getWidth();
        mFlyout.setCollapsePercent(Math.min(1f, Math.max(0f, collapsePercent)));

        // Calculate how to translate the flyout if it has been dragged too far in either direction.
        float overscrollTranslation = 0f;
        if (collapsePercent < 0f || collapsePercent > 1f) {
            // Whether we are more than 100% transitioned to the dot.
            final boolean overscrollingPastDot = collapsePercent > 1f;

            // Whether we are overscrolling physically to the left - this can either be pulling the
            // flyout away from the stack (if the stack is on the right) or pushing it to the left
            // after it has already become the dot.
            final boolean overscrollingLeft =
                    (onLeft && collapsePercent > 1f) || (!onLeft && collapsePercent < 0f);
            overscrollTranslation =
                    (overscrollingPastDot ? collapsePercent - 1f : collapsePercent * -1)
                            * (overscrollingLeft ? -1 : 1)
                            * (mFlyout.getWidth() / (FLYOUT_OVERSCROLL_ATTENUATION_FACTOR
                            // Attenuate the smaller dot less than the larger flyout.
                            / (overscrollingPastDot ? 2 : 1)));
        }

        mFlyout.setTranslationX(mFlyout.getRestingTranslationX() + overscrollTranslation);
    }

    /** Passes the MotionEvent to the magnetized object and returns true if it was consumed. */
    private boolean passEventToMagnetizedObject(MotionEvent event) {
        return mMagnetizedObject != null && mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    /**
     * Dismisses the magnetized object - either an individual bubble, if we're expanded, or the
     * stack, if we're collapsed.
     */
    private void dismissMagnetizedObject() {
        if (mIsExpanded) {
            final View draggedOutBubbleView = (View) mMagnetizedObject.getUnderlyingObject();
            dismissBubbleIfExists(mBubbleData.getBubbleWithView(draggedOutBubbleView));
        } else {
            mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        }
    }

    private void dismissBubbleIfExists(@Nullable BubbleViewProvider bubble) {
        if (bubble != null && mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
            mBubbleData.dismissBubbleWithKey(bubble.getKey(), Bubbles.DISMISS_USER_GESTURE);
        }
    }

    /** Prepares and starts the desaturate/darken animation on the bubble stack. */
    private void animateDesaturateAndDarken(View targetView, boolean desaturateAndDarken) {
        mDesaturateAndDarkenTargetView = targetView;

        if (mDesaturateAndDarkenTargetView == null) {
            return;
        }

        if (desaturateAndDarken) {
            // Use the animated paint for the bubbles.
            mDesaturateAndDarkenTargetView.setLayerType(
                    View.LAYER_TYPE_HARDWARE, mDesaturateAndDarkenPaint);
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.start();
        } else {
            mDesaturateAndDarkenAnimator.removeAllListeners();
            mDesaturateAndDarkenAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    // Stop using the animated paint.
                    resetDesaturationAndDarken();
                }
            });
            mDesaturateAndDarkenAnimator.reverse();
        }
    }

    private void resetDesaturationAndDarken() {

        mDesaturateAndDarkenAnimator.removeAllListeners();
        mDesaturateAndDarkenAnimator.cancel();

        if (mDesaturateAndDarkenTargetView != null) {
            mDesaturateAndDarkenTargetView.setLayerType(View.LAYER_TYPE_NONE, null);
            mDesaturateAndDarkenTargetView = null;
        }
    }

    /** Animates the flyout collapsed (to dot), or the reverse, starting with the given velocity. */
    private void animateFlyoutCollapsed(boolean collapsed, float velX) {
        final boolean onLeft = mStackAnimationController.isStackOnLeftSide();
        // If the flyout was tapped, we want a higher stiffness for the collapse animation so it's
        // faster.
        mFlyoutTransitionSpring.getSpring().setStiffness(
                (mBubbleToExpandAfterFlyoutCollapse != null)
                        ? SpringForce.STIFFNESS_MEDIUM
                        : SpringForce.STIFFNESS_LOW);
        mFlyoutTransitionSpring
                .setStartValue(mFlyoutDragDeltaX)
                .setStartVelocity(velX)
                .animateToFinalPosition(collapsed
                        ? (onLeft ? -mFlyout.getWidth() : mFlyout.getWidth())
                        : 0f);
    }

    private boolean shouldShowFlyout(Bubble bubble) {
        Bubble.FlyoutMessage flyoutMessage = bubble.getFlyoutMessage();
        final BadgedImageView bubbleView = bubble.getIconView();
        if (flyoutMessage == null
                || flyoutMessage.message == null
                || !bubble.showFlyout()
                || (mStackEduView != null && mStackEduView.getVisibility() == VISIBLE)
                || isExpanded()
                || mIsExpansionAnimating
                || mIsGestureInProgress
                || mBubbleToExpandAfterFlyoutCollapse != null
                || bubbleView == null) {
            if (bubbleView != null && mFlyout.getVisibility() != VISIBLE) {
                bubbleView.removeDotSuppressionFlag(BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);
            }
            // Skip the message if none exists, we're expanded or animating expansion, or we're
            // about to expand a bubble from the previous tapped flyout, or if bubble view is null.
            return false;
        }
        return true;
    }

    /**
     * Animates in the flyout for the given bubble, if available, and then hides it after some time.
     */
    @VisibleForTesting
    void animateInFlyoutForBubble(Bubble bubble) {
        if (!shouldShowFlyout(bubble)) {
            return;
        }

        mFlyoutDragDeltaX = 0f;
        clearFlyoutOnHide();
        mAfterFlyoutHidden = () -> {
            // Null it out to ensure it runs once.
            mAfterFlyoutHidden = null;

            if (mBubbleToExpandAfterFlyoutCollapse != null) {
                // User tapped on the flyout and we should expand
                mBubbleData.setSelectedBubble(mBubbleToExpandAfterFlyoutCollapse);
                mBubbleData.setExpanded(true);
                mBubbleToExpandAfterFlyoutCollapse = null;
            }

            // Stop suppressing the dot now that the flyout has morphed into the dot.
            if (bubble.getIconView() != null) {
                bubble.getIconView().removeDotSuppressionFlag(
                        BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);
            }
            // Hide the stack after a delay, if needed.
            updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
        };

        // Suppress the dot when we are animating the flyout.
        bubble.getIconView().addDotSuppressionFlag(
                BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);

        // Start flyout expansion. Post in case layout isn't complete and getWidth returns 0.
        post(() -> {
            // An auto-expanding bubble could have been posted during the time it takes to
            // layout.
            if (isExpanded() || bubble.getIconView() == null) {
                return;
            }
            final Runnable expandFlyoutAfterDelay = () -> {
                mAnimateInFlyout = () -> {
                    mFlyout.setVisibility(VISIBLE);
                    updateTemporarilyInvisibleAnimation(false /* hideImmediately */);
                    mFlyoutDragDeltaX =
                            mStackAnimationController.isStackOnLeftSide()
                                    ? -mFlyout.getWidth()
                                    : mFlyout.getWidth();
                    animateFlyoutCollapsed(false /* collapsed */, 0 /* velX */);
                    mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
                };
                mFlyout.postDelayed(mAnimateInFlyout, 200);
            };


            if (mFlyout.getVisibility() == View.VISIBLE) {
                mFlyout.animateUpdate(bubble.getFlyoutMessage(), getWidth(),
                        mStackAnimationController.getStackPosition(), !bubble.showDot());
            } else {
                mFlyout.setVisibility(INVISIBLE);
                mFlyout.setupFlyoutStartingAsDot(bubble.getFlyoutMessage(),
                        mStackAnimationController.getStackPosition(), getWidth(),
                        mStackAnimationController.isStackOnLeftSide(),
                        bubble.getIconView().getDotColor() /* dotColor */,
                        expandFlyoutAfterDelay /* onLayoutComplete */,
                        mAfterFlyoutHidden,
                        bubble.getIconView().getDotCenter(),
                        !bubble.showDot(),
                        mPositioner);
            }
            mFlyout.bringToFront();
        });
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.postDelayed(mHideFlyout, FLYOUT_HIDE_AFTER);
        logBubbleEvent(bubble, FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__FLYOUT);
    }

    /** Hide the flyout immediately and cancel any pending hide runnables. */
    private void hideFlyoutImmediate() {
        clearFlyoutOnHide();
        mFlyout.removeCallbacks(mAnimateInFlyout);
        mFlyout.removeCallbacks(mHideFlyout);
        mFlyout.hideFlyout();
    }

    private void clearFlyoutOnHide() {
        mFlyout.removeCallbacks(mAnimateInFlyout);
        if (mAfterFlyoutHidden == null) {
            return;
        }
        mAfterFlyoutHidden.run();
        mAfterFlyoutHidden = null;
    }

    /**
     * Fills the Rect with the touchable region of the bubbles. This will be used by WindowManager
     * to decide which touch events go to Bubbles.
     *
     * Bubbles is below the status bar/notification shade but above application windows. If you're
     * trying to get touch events from the status bar or another higher-level window layer, you'll
     * need to re-order TYPE_BUBBLES in WindowManagerPolicy so that we have the opportunity to steal
     * them.
     */
    public void getTouchableRegion(Rect outRect) {
        if (mStackEduView != null && mStackEduView.getVisibility() == VISIBLE) {
            // When user education shows then capture all touches
            outRect.set(0, 0, getWidth(), getHeight());
            return;
        }

        if (!mIsExpanded) {
            if (getBubbleCount() > 0 || mBubbleData.isShowingOverflow()) {
                mBubbleContainer.getChildAt(0).getBoundsOnScreen(outRect);
                // Increase the touch target size of the bubble
                outRect.top -= mBubbleTouchPadding;
                outRect.left -= mBubbleTouchPadding;
                outRect.right += mBubbleTouchPadding;
                outRect.bottom += mBubbleTouchPadding;
            }
        } else {
            mBubbleContainer.getBoundsOnScreen(outRect);
        }

        if (mFlyout.getVisibility() == View.VISIBLE) {
            final Rect flyoutBounds = new Rect();
            mFlyout.getBoundsOnScreen(flyoutBounds);
            outRect.union(flyoutBounds);
        }
    }

    private void requestUpdate() {
        if (mViewUpdatedRequested || mIsExpansionAnimating) {
            return;
        }
        mViewUpdatedRequested = true;
        getViewTreeObserver().addOnPreDrawListener(mViewUpdater);
        invalidate();
    }

    private void showManageMenu(boolean show) {
        mShowingManage = show;

        // This should not happen, since the manage menu is only visible when there's an expanded
        // bubble. If we end up in this state, just hide the menu immediately.
        if (mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            mManageMenu.setVisibility(View.INVISIBLE);
            return;
        }

        // If available, update the manage menu's settings option with the expanded bubble's app
        // name and icon.
        if (show && mBubbleData.hasBubbleInStackWithKey(mExpandedBubble.getKey())) {
            final Bubble bubble = mBubbleData.getBubbleInStackWithKey(mExpandedBubble.getKey());
            mManageSettingsIcon.setImageBitmap(bubble.getAppBadge());
            mManageSettingsText.setText(getResources().getString(
                    R.string.bubbles_app_settings, bubble.getAppName()));
        }

        mExpandedBubble.getExpandedView().getManageButtonBoundsOnScreen(mTempRect);
        if (mExpandedBubble.getExpandedView().getTaskView() != null) {
            mExpandedBubble.getExpandedView().getTaskView().setObscuredTouchRect(mShowingManage
                    ? new Rect(0, 0, getWidth(), getHeight())
                    : null);
        }

        final boolean isLtr =
                getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_LTR;

        // When the menu is open, it should be at these coordinates. The menu pops out to the right
        // in LTR and to the left in RTL.
        final float targetX = isLtr ? mTempRect.left : mTempRect.right - mManageMenu.getWidth();
        final float targetY = mTempRect.bottom - mManageMenu.getHeight();

        final float xOffsetForAnimation = (isLtr ? 1 : -1) * mManageMenu.getWidth() / 4f;
        if (show) {
            mManageMenu.setScaleX(0.5f);
            mManageMenu.setScaleY(0.5f);
            mManageMenu.setTranslationX(targetX - xOffsetForAnimation);
            mManageMenu.setTranslationY(targetY + mManageMenu.getHeight() / 4f);
            mManageMenu.setAlpha(0f);

            PhysicsAnimator.getInstance(mManageMenu)
                    .spring(DynamicAnimation.ALPHA, 1f)
                    .spring(DynamicAnimation.SCALE_X, 1f)
                    .spring(DynamicAnimation.SCALE_Y, 1f)
                    .spring(DynamicAnimation.TRANSLATION_X, targetX)
                    .spring(DynamicAnimation.TRANSLATION_Y, targetY)
                    .withEndActions(() -> {
                        View child = mManageMenu.getChildAt(0);
                        child.requestAccessibilityFocus();
                        // Update the AV's obscured touchable region for the new visibility state.
                        mExpandedBubble.getExpandedView().updateObscuredTouchableRegion();
                    })
                    .start();

            mManageMenu.setVisibility(View.VISIBLE);
        } else {
            PhysicsAnimator.getInstance(mManageMenu)
                    .spring(DynamicAnimation.ALPHA, 0f)
                    .spring(DynamicAnimation.SCALE_X, 0.5f)
                    .spring(DynamicAnimation.SCALE_Y, 0.5f)
                    .spring(DynamicAnimation.TRANSLATION_X, targetX - xOffsetForAnimation)
                    .spring(DynamicAnimation.TRANSLATION_Y, targetY + mManageMenu.getHeight() / 4f)
                    .withEndActions(() -> {
                        mManageMenu.setVisibility(View.INVISIBLE);
                        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
                            // Update the AV's obscured touchable region for the new state.
                            mExpandedBubble.getExpandedView().updateObscuredTouchableRegion();
                        }
                    })
                    .start();
        }
    }

    private void updateExpandedBubble() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedBubble()");
        }

        mExpandedViewContainer.removeAllViews();
        if (mIsExpanded && mExpandedBubble != null
                && mExpandedBubble.getExpandedView() != null) {
            BubbleExpandedView bev = mExpandedBubble.getExpandedView();
            bev.setContentVisibility(false);
            bev.setAlphaAnimating(!mIsExpansionAnimating);
            mExpandedViewContainerMatrix.setScaleX(0f);
            mExpandedViewContainerMatrix.setScaleY(0f);
            mExpandedViewContainerMatrix.setTranslate(0f, 0f);
            mExpandedViewContainer.setVisibility(View.INVISIBLE);
            mExpandedViewContainer.setAlpha(0f);
            mExpandedViewContainer.addView(bev);
            bev.setManageClickListener((view) -> showManageMenu(!mShowingManage));

            if (!mIsExpansionAnimating) {
                mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                    post(this::animateSwitchBubbles);
                });
            }
        }
    }

    /**
     * Requests a snapshot from the currently expanded bubble's ActivityView and displays it in a
     * SurfaceView. This allows us to load a newly expanded bubble's Activity into the ActivityView,
     * while animating the (screenshot of the) previously selected bubble's content away.
     *
     * @param onComplete Callback to run once we're done here - called with 'false' if something
     *                   went wrong, or 'true' if the SurfaceView is now showing a screenshot of the
     *                   expanded bubble.
     */
    private void screenshotAnimatingOutBubbleIntoSurface(Consumer<Boolean> onComplete) {
        if (!mIsExpanded || mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            // You can't animate null.
            onComplete.accept(false);
            return;
        }

        final BubbleExpandedView animatingOutExpandedView = mExpandedBubble.getExpandedView();

        // Release the previous screenshot if it hasn't been released already.
        if (mAnimatingOutBubbleBuffer != null) {
            releaseAnimatingOutBubbleBuffer();
        }

        try {
            mAnimatingOutBubbleBuffer = animatingOutExpandedView.snapshotActivitySurface();
        } catch (Exception e) {
            // If we fail for any reason, print the stack trace and then notify the callback of our
            // failure. This is not expected to occur, but it's not worth crashing over.
            Log.wtf(TAG, e);
            onComplete.accept(false);
        }

        if (mAnimatingOutBubbleBuffer == null
                || mAnimatingOutBubbleBuffer.getHardwareBuffer() == null) {
            // While no exception was thrown, we were unable to get a snapshot.
            onComplete.accept(false);
            return;
        }

        // Make sure the surface container's properties have been reset.
        PhysicsAnimator.getInstance(mAnimatingOutSurfaceContainer).cancel();
        mAnimatingOutSurfaceContainer.setScaleX(1f);
        mAnimatingOutSurfaceContainer.setScaleY(1f);
        mAnimatingOutSurfaceContainer.setTranslationX(mExpandedViewContainer.getPaddingLeft());
        mAnimatingOutSurfaceContainer.setTranslationY(0);

        final int[] activityViewLocation =
                mExpandedBubble.getExpandedView().getTaskViewLocationOnScreen();
        final int[] surfaceViewLocation = mAnimatingOutSurfaceView.getLocationOnScreen();

        // Translate the surface to overlap the real ActivityView.
        mAnimatingOutSurfaceContainer.setTranslationY(
                activityViewLocation[1] - surfaceViewLocation[1]);

        // Set the width/height of the SurfaceView to match the snapshot.
        mAnimatingOutSurfaceView.getLayoutParams().width =
                mAnimatingOutBubbleBuffer.getHardwareBuffer().getWidth();
        mAnimatingOutSurfaceView.getLayoutParams().height =
                mAnimatingOutBubbleBuffer.getHardwareBuffer().getHeight();
        mAnimatingOutSurfaceView.requestLayout();

        // Post to wait for layout.
        post(() -> {
            // The buffer might have been destroyed if the user is mashing on bubbles, that's okay.
            if (mAnimatingOutBubbleBuffer == null
                    || mAnimatingOutBubbleBuffer.getHardwareBuffer() == null
                    || mAnimatingOutBubbleBuffer.getHardwareBuffer().isClosed()) {
                onComplete.accept(false);
                return;
            }

            if (!mIsExpanded) {
                onComplete.accept(false);
                return;
            }

            // Attach the buffer! We're now displaying the snapshot.
            mAnimatingOutSurfaceView.getHolder().getSurface().attachAndQueueBufferWithColorSpace(
                    mAnimatingOutBubbleBuffer.getHardwareBuffer(),
                    mAnimatingOutBubbleBuffer.getColorSpace());

            mAnimatingOutSurfaceView.setAlpha(1f);
            mExpandedViewContainer.setVisibility(View.GONE);

            mSurfaceSynchronizer.syncSurfaceAndRun(() -> {
                post(() -> {
                    onComplete.accept(true);
                });
            });
        });
    }

    /**
     * Releases the buffer containing the screenshot of the animating-out bubble, if it exists and
     * isn't yet destroyed.
     */
    private void releaseAnimatingOutBubbleBuffer() {
        if (mAnimatingOutBubbleBuffer != null
                && !mAnimatingOutBubbleBuffer.getHardwareBuffer().isClosed()) {
            mAnimatingOutBubbleBuffer.getHardwareBuffer().close();
        }
    }

    private void updateExpandedView() {
        if (DEBUG_BUBBLE_STACK_VIEW) {
            Log.d(TAG, "updateExpandedView: mIsExpanded=" + mIsExpanded);
        }
        boolean isOverflowExpanded = mExpandedBubble != null
                && BubbleOverflow.KEY.equals(mExpandedBubble.getKey());
        int[] paddings = mPositioner.getExpandedViewPadding(
                mStackAnimationController.isStackOnLeftSide(), isOverflowExpanded);
        mExpandedViewContainer.setPadding(paddings[0], paddings[1], paddings[2], paddings[3]);
        if (mIsExpansionAnimating) {
            mExpandedViewContainer.setVisibility(mIsExpanded ? VISIBLE : GONE);
        }
        if (mExpandedBubble != null && mExpandedBubble.getExpandedView() != null) {
            mExpandedViewContainer.setTranslationY(mPositioner.getExpandedViewY());
            mExpandedViewContainer.setTranslationX(0f);
            mExpandedBubble.getExpandedView().updateView(
                    mExpandedViewContainer.getLocationOnScreen());
            updatePointerPosition();
        }

        mStackOnLeftOrWillBe = mStackAnimationController.isStackOnLeftSide();
    }

    /**
     * Sets the appropriate Z-order, badge, and dot position for each bubble in the stack.
     * Animate dot and badge changes.
     */
    private void updateBadgesAndZOrder(boolean setBadgeForCollapsedStack) {
        int bubbleCount = getBubbleCount();
        for (int i = 0; i < bubbleCount; i++) {
            BadgedImageView bv = (BadgedImageView) mBubbleContainer.getChildAt(i);
            bv.setZ((mMaxBubbles * mBubbleElevation) - i);
            if (mIsExpanded) {
                // If we're not displaying vertically, we always show the badge on the left.
                boolean onLeft = mPositioner.showBubblesVertically() && !mStackOnLeftOrWillBe;
                bv.showDotAndBadge(onLeft);
            } else if (setBadgeForCollapsedStack) {
                if (i == 0) {
                    bv.showDotAndBadge(!mStackOnLeftOrWillBe);
                } else {
                    bv.hideDotAndBadge(!mStackOnLeftOrWillBe);
                }
            }
        }
    }

    private void updatePointerPosition() {
        if (mExpandedBubble == null || mExpandedBubble.getExpandedView() == null) {
            return;
        }
        int index = getBubbleIndex(mExpandedBubble);
        if (index == -1) {
            return;
        }
        float bubblePosition = mExpandedAnimationController.getBubbleXOrYForOrientation(index);
        mExpandedBubble.getExpandedView().setPointerPosition(bubblePosition, mStackOnLeftOrWillBe);
    }

    /**
     * @return the number of bubbles in the stack view.
     */
    public int getBubbleCount() {
        // Subtract 1 for the overflow button that is always in the bubble container.
        return mBubbleContainer.getChildCount() - 1;
    }

    /**
     * Finds the bubble index within the stack.
     *
     * @param provider the bubble view provider with the bubble to look up.
     * @return the index of the bubble view within the bubble stack. The range of the position
     * is between 0 and the bubble count minus 1.
     */
    int getBubbleIndex(@Nullable BubbleViewProvider provider) {
        if (provider == null) {
            return 0;
        }
        return mBubbleContainer.indexOfChild(provider.getIconView());
    }

    /**
     * @return the normalized x-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedXPosition() {
        return new BigDecimal(getStackPosition().x / mPositioner.getAvailableRect().width())
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /**
     * @return the normalized y-axis position of the bubble stack rounded to 4 decimal places.
     */
    public float getNormalizedYPosition() {
        return new BigDecimal(getStackPosition().y / mPositioner.getAvailableRect().height())
                .setScale(4, RoundingMode.CEILING.HALF_UP)
                .floatValue();
    }

    /** @return the position of the bubble stack. */
    public PointF getStackPosition() {
        return mStackAnimationController.getStackPosition();
    }

    /**
     * Logs the bubble UI event.
     *
     * @param provider the bubble view provider that is being interacted on. Null value indicates
     *               that the user interaction is not specific to one bubble.
     * @param action the user interaction enum.
     */
    private void logBubbleEvent(@Nullable BubbleViewProvider provider, int action) {
        final String packageName =
                (provider != null && provider instanceof Bubble)
                    ? ((Bubble) provider).getPackageName()
                    : "null";
        mBubbleData.logBubbleEvent(provider,
                action,
                packageName,
                getBubbleCount(),
                getBubbleIndex(provider),
                getNormalizedXPosition(),
                getNormalizedYPosition());
    }

    /** For debugging only */
    List<Bubble> getBubblesOnScreen() {
        List<Bubble> bubbles = new ArrayList<>();
        for (int i = 0; i < getBubbleCount(); i++) {
            View child = mBubbleContainer.getChildAt(i);
            if (child instanceof BadgedImageView) {
                String key = ((BadgedImageView) child).getKey();
                Bubble bubble = mBubbleData.getBubbleInStackWithKey(key);
                bubbles.add(bubble);
            }
        }
        return bubbles;
    }

    /**
     * Representation of stack position that uses relative properties rather than absolute
     * coordinates. This is used to maintain similar stack positions across configuration changes.
     */
    public static class RelativeStackPosition {
        /** Whether to place the stack at the leftmost allowed position. */
        private boolean mOnLeft;

        /**
         * How far down the vertically allowed region to place the stack. For example, if the stack
         * allowed region is between y = 100 and y = 1100 and this is 0.2f, we'll place the stack at
         * 100 + (0.2f * 1000) = 300.
         */
        private float mVerticalOffsetPercent;

        public RelativeStackPosition(boolean onLeft, float verticalOffsetPercent) {
            mOnLeft = onLeft;
            mVerticalOffsetPercent = clampVerticalOffsetPercent(verticalOffsetPercent);
        }

        /** Constructs a relative position given a region and a point in that region. */
        public RelativeStackPosition(PointF position, RectF region) {
            mOnLeft = position.x < region.width() / 2;
            mVerticalOffsetPercent =
                    clampVerticalOffsetPercent((position.y - region.top) / region.height());
        }

        /** Ensures that the offset percent is between 0f and 1f. */
        private float clampVerticalOffsetPercent(float offsetPercent) {
            return Math.max(0f, Math.min(1f, offsetPercent));
        }

        /**
         * Given an allowable stack position region, returns the point within that region
         * represented by this relative position.
         */
        public PointF getAbsolutePositionInRegion(RectF region) {
            return new PointF(
                    mOnLeft ? region.left : region.right,
                    region.top + mVerticalOffsetPercent * region.height());
        }
    }
}
