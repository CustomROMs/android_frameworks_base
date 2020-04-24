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

package com.android.systemui.car.notification;

import android.app.ActivityManager;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarNotificationView;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationDataManager;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.window.OverlayPanelViewController;
import com.android.systemui.car.window.OverlayViewGlobalStateController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;

import javax.inject.Inject;
import javax.inject.Singleton;

/** View controller for the notification panel. */
@Singleton
public class NotificationPanelViewController extends OverlayPanelViewController {

    private static final boolean DEBUG = true;
    private static final String TAG = "NotificationPanelViewController";

    private final Context mContext;
    private final Resources mResources;
    private final CarServiceProvider mCarServiceProvider;
    private final IStatusBarService mBarService;
    private final CommandQueue mCommandQueue;
    private final NotificationDataManager mNotificationDataManager;
    private final CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper;
    private final CarNotificationListener mCarNotificationListener;
    private final NotificationClickHandlerFactory mNotificationClickHandlerFactory;
    private final StatusBarStateController mStatusBarStateController;

    private float mInitialBackgroundAlpha;
    private float mBackgroundAlphaDiff;

    private CarNotificationView mNotificationView;
    private View mHandleBar;
    private RecyclerView mNotificationList;
    private NotificationViewController mNotificationViewController;

    private boolean mIsTracking;
    private boolean mNotificationListAtBottom;
    private float mFirstTouchDownOnGlassPane;
    private boolean mNotificationListAtBottomAtTimeOfTouch;
    private boolean mIsSwipingVerticallyToClose;
    private boolean mIsNotificationCardSwiping;

    private OnUnseenCountUpdateListener mUnseenCountUpdateListener;

    @Inject
    public NotificationPanelViewController(
            Context context,
            @Main Resources resources,
            OverlayViewGlobalStateController overlayViewGlobalStateController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,

            /* Other things */
            CarServiceProvider carServiceProvider,
            CarDeviceProvisionedController carDeviceProvisionedController,

            /* Things needed for notifications */
            IStatusBarService barService,
            CommandQueue commandQueue,
            NotificationDataManager notificationDataManager,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            CarNotificationListener carNotificationListener,
            NotificationClickHandlerFactory notificationClickHandlerFactory,

            /* Things that need to be replaced */
            StatusBarStateController statusBarStateController
    ) {
        super(context, resources, R.id.notification_panel_stub, overlayViewGlobalStateController,
                flingAnimationUtilsBuilder, carDeviceProvisionedController);
        mContext = context;
        mResources = resources;
        mCarServiceProvider = carServiceProvider;
        mBarService = barService;
        mCommandQueue = commandQueue;
        mNotificationDataManager = notificationDataManager;
        mCarUxRestrictionManagerWrapper = carUxRestrictionManagerWrapper;
        mCarNotificationListener = carNotificationListener;
        mNotificationClickHandlerFactory = notificationClickHandlerFactory;
        mStatusBarStateController = statusBarStateController;

        // Notification background setup.
        mInitialBackgroundAlpha = (float) mResources.getInteger(
                R.integer.config_initialNotificationBackgroundAlpha) / 100;
        if (mInitialBackgroundAlpha < 0 || mInitialBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect initial background alpha"
                            + " percentage");
        }
        float finalBackgroundAlpha = Math.max(
                mInitialBackgroundAlpha,
                (float) mResources.getInteger(
                        R.integer.config_finalNotificationBackgroundAlpha) / 100);
        if (finalBackgroundAlpha < 0 || finalBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect final background alpha"
                            + " percentage");
        }
        mBackgroundAlphaDiff = finalBackgroundAlpha - mInitialBackgroundAlpha;
    }

    @Override
    protected void onFinishInflate() {
        reinflate();
    }

    /** Reinflates the view. */
    public void reinflate() {
        ViewGroup container = (ViewGroup) getLayout();
        container.removeView(mNotificationView);

        mNotificationView = (CarNotificationView) LayoutInflater.from(mContext).inflate(
                R.layout.notification_center_activity, container,
                /* attachToRoot= */ false);

        container.addView(mNotificationView);
        onNotificationViewInflated();
    }

    private void onNotificationViewInflated() {
        // Find views.
        mNotificationView = getLayout().findViewById(R.id.notification_view);
        setupHandleBar();
        setupNotificationPanel();

        mNotificationClickHandlerFactory.registerClickListener((launchResult, alertEntry) -> {
            if (launchResult == ActivityManager.START_TASK_TO_FRONT
                    || launchResult == ActivityManager.START_SUCCESS) {
                animateCollapsePanel();
            }
        });

        mNotificationDataManager.setOnUnseenCountUpdateListener(() -> {
            if (mUnseenCountUpdateListener != null) {
                mUnseenCountUpdateListener.onUnseenCountUpdate(
                        mNotificationDataManager.getUnseenNotificationCount());
            }
        });

        mNotificationClickHandlerFactory.setNotificationDataManager(mNotificationDataManager);
        mNotificationView.setClickHandlerFactory(mNotificationClickHandlerFactory);
        mNotificationView.setNotificationDataManager(mNotificationDataManager);

        mCarServiceProvider.addListener(car -> {
            CarUxRestrictionsManager carUxRestrictionsManager =
                    (CarUxRestrictionsManager)
                            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            mCarUxRestrictionManagerWrapper.setCarUxRestrictionsManager(
                    carUxRestrictionsManager);

            mNotificationViewController = new NotificationViewController(
                    mNotificationView,
                    PreprocessingManager.getInstance(mContext),
                    mCarNotificationListener,
                    mCarUxRestrictionManagerWrapper,
                    mNotificationDataManager);
            mNotificationViewController.enable();
        });
    }

    private void setupHandleBar() {
        mHandleBar = mNotificationView.findViewById(R.id.handle_bar);
        GestureDetector handleBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new HandleBarCloseGestureListener());
        mHandleBar.setOnTouchListener((v, event) -> {
            handleBarCloseNotificationGestureDetector.onTouchEvent(event);
            maybeCompleteAnimation(event);
            return true;
        });
    }

    private void setupNotificationPanel() {
        View glassPane = mNotificationView.findViewById(R.id.glass_pane);
        mNotificationList = mNotificationView.findViewById(R.id.notifications);
        GestureDetector closeGestureDetector = new GestureDetector(mContext,
                new CloseGestureListener() {
                    @Override
                    protected void close() {
                        if (isPanelExpanded()) {
                            animateCollapsePanel();
                        }
                    }
                });

        // The glass pane is used to view touch events before passed to the notification list.
        // This allows us to initialize gesture listeners and detect when to close the notifications
        glassPane.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFirstTouchDownOnGlassPane = event.getRawX();
                mNotificationListAtBottomAtTimeOfTouch = mNotificationListAtBottom;
                // Reset the tracker when there is a touch down on the glass pane.
                mIsTracking = false;
                // Pass the down event to gesture detector so that it knows where the touch event
                // started.
                closeGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        mNotificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // Check if we can scroll vertically downwards.
                if (!mNotificationList.canScrollVertically(/* direction= */ 1)) {
                    mNotificationListAtBottom = true;
                    return;
                }
                mNotificationListAtBottom = false;
                mIsSwipingVerticallyToClose = false;
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
        });

        mNotificationList.setOnTouchListener((v, event) -> {
            mIsNotificationCardSwiping = Math.abs(mFirstTouchDownOnGlassPane - event.getRawX())
                    > SWIPE_MAX_OFF_PATH;
            if (mNotificationListAtBottomAtTimeOfTouch && mNotificationListAtBottom) {
                // We need to save the state here as if notification card is swiping we will
                // change the mNotificationListAtBottomAtTimeOfTouch. This is to protect
                // closing the notification shade while the notification card is being swiped.
                mIsSwipingVerticallyToClose = true;
            }

            // If the card is swiping we should not allow the notification shade to close.
            // Hence setting mNotificationListAtBottomAtTimeOfTouch to false will stop that
            // for us. We are also checking for mIsTracking because while swiping the
            // notification shade to close if the user goes a bit horizontal while swiping
            // upwards then also this should close.
            if (mIsNotificationCardSwiping && !mIsTracking) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }

            boolean handled = closeGestureDetector.onTouchEvent(event);
            boolean isTracking = mIsTracking;
            Rect rect = getLayout().getClipBounds();
            float clippedHeight = 0;
            if (rect != null) {
                clippedHeight = rect.bottom;
            }
            if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP
                    && mIsSwipingVerticallyToClose) {
                if (getSettleClosePercentage() < getPercentageFromBottom() && isTracking) {
                    animatePanel(DEFAULT_FLING_VELOCITY, false);
                } else if (clippedHeight != getLayout().getHeight() && isTracking) {
                    // this can be caused when user is at the end of the list and trying to
                    // fling to top of the list by scrolling down.
                    animatePanel(DEFAULT_FLING_VELOCITY, true);
                }
            }

            // Updating the mNotificationListAtBottomAtTimeOfTouch state has to be done after
            // the event has been passed to the closeGestureDetector above, such that the
            // closeGestureDetector sees the up event before the state has changed.
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            return handled || isTracking;
        });
    }

    /** Called when the car power state is changed to ON. */
    public void onCarPowerStateOn() {
        if (mNotificationClickHandlerFactory != null) {
            mNotificationClickHandlerFactory.clearAllNotifications();
        }
        mNotificationDataManager.clearAll();
    }

    @Override
    protected boolean shouldAnimateCollapsePanel() {
        return true;
    }

    @Override
    protected void onAnimateCollapsePanel() {
        // No op.
    }

    @Override
    protected boolean shouldAnimateExpandPanel() {
        return mCommandQueue.panelsEnabled();
    }

    @Override
    protected void onAnimateExpandPanel() {
        mNotificationList.scrollToPosition(0);
    }

    @Override
    protected void onCollapseAnimationEnd() {
        mNotificationViewController.onVisibilityChanged(false);
    }

    @Override
    protected void onExpandAnimationEnd() {
        mNotificationViewController.onVisibilityChanged(true);
        mNotificationView.setVisibleNotificationsAsSeen();
    }

    @Override
    protected void onPanelExpanded(boolean expand) {
        super.onPanelExpanded(expand);

        if (expand && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            if (DEBUG) {
                Log.v(TAG, "clearing notification effects from setExpandedHeight");
            }
            clearNotificationEffects();
        }
    }

    /**
     * Clear Buzz/Beep/Blink.
     */
    private void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    @Override
    protected void onOpenScrollStart() {
        mNotificationList.scrollToPosition(0);
    }

    @Override
    protected void onScroll(int height) {
        if (mHandleBar != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mHandleBar.getLayoutParams();
            mHandleBar.setTranslationY(height - mHandleBar.getHeight() - lp.bottomMargin);
        }

        if (mNotificationView.getHeight() > 0) {
            Drawable background = mNotificationView.getBackground().mutate();
            background.setAlpha((int) (getBackgroundAlpha(height) * 255));
            mNotificationView.setBackground(background);
        }
    }

    @Override
    protected boolean shouldAllowClosingScroll() {
        // Unless the notification list is at the bottom, the panel shouldn't be allowed to
        // collapse on scroll.
        return mNotificationListAtBottomAtTimeOfTouch;
    }

    /**
     * Calculates the alpha value for the background based on how much of the notification
     * shade is visible to the user. When the notification shade is completely open then
     * alpha value will be 1.
     */
    private float getBackgroundAlpha(int height) {
        return mInitialBackgroundAlpha
                + ((float) height / mNotificationView.getHeight() * mBackgroundAlphaDiff);
    }

    /** Sets the unseen count listener. */
    public void setOnUnseenCountUpdateListener(OnUnseenCountUpdateListener listener) {
        mUnseenCountUpdateListener = listener;
    }

    /** Listener that is updated when the number of unseen notifications changes. */
    public interface OnUnseenCountUpdateListener {
        /**
         * This method is automatically called whenever there is an update to the number of unseen
         * notifications. This method can be extended by OEMs to customize the desired logic.
         */
        void onUnseenCountUpdate(int unseenNotificationCount);
    }

    /**
     * To be installed on the handle bar.
     */
    private class HandleBarCloseGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            // To prevent the jump in the clip bounds while closing the notification shade using
            // the handle bar we should calculate the height using the diff of event1 and event2.
            // This will help the notification shade to clip smoothly as the event2 value changes
            // as event1 value will be fixed.
            int clipHeight = getLayout().getHeight() - (int) (event1.getRawY() - event2.getRawY());
            setViewClipBounds(clipHeight);
            return true;
        }
    }
}
