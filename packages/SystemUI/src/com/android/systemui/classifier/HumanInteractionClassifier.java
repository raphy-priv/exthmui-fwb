/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.SensorEvent;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * An classifier trying to determine whether it is a human interacting with the phone or not.
 */
public class HumanInteractionClassifier extends Classifier {
    private static final String HIC_ENABLE = "HIC_enable";
    private static final float FINGER_DISTANCE = 0.1f;

    /** Default value for the HIC_ENABLE setting: 1 - enabled, 0 - disabled */
    private static final int HIC_ENABLE_DEFAULT = 1;

    private static HumanInteractionClassifier sInstance = null;

    private final Handler mHandler = new Handler();
    private final Context mContext;

    private ArrayList<StrokeClassifier> mStrokeClassifiers = new ArrayList<>();
    private ArrayList<GestureClassifier> mGestureClassifiers = new ArrayList<>();
    private ArrayDeque<MotionEvent> mBufferedEvents = new ArrayDeque<>();
    private final int mStrokeClassifiersSize;
    private final int mGestureClassifiersSize;
    private final float mDpi;

    private HistoryEvaluator mHistoryEvaluator;
    private boolean mEnableClassifier = false;
    private int mCurrentType = Classifier.GENERIC;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private HumanInteractionClassifier(Context context) {
        mContext = context;
        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();

        // If the phone is rotated to landscape, the calculations would be wrong if xdpi and ydpi
        // were to be used separately. Due negligible differences in xdpi and ydpi we can just
        // take the average.
        mDpi = (displayMetrics.xdpi + displayMetrics.ydpi) / 2.0f;
        mClassifierData = new ClassifierData(mDpi);
        mHistoryEvaluator = new HistoryEvaluator();

        mStrokeClassifiers.add(new AnglesClassifier(mClassifierData));
        mStrokeClassifiers.add(new SpeedClassifier(mClassifierData));
        mStrokeClassifiers.add(new DurationCountClassifier(mClassifierData));
        mStrokeClassifiers.add(new EndPointRatioClassifier(mClassifierData));
        mStrokeClassifiers.add(new EndPointLengthClassifier(mClassifierData));
        mStrokeClassifiers.add(new AccelerationClassifier(mClassifierData));
        mStrokeClassifiers.add(new SpeedAnglesClassifier(mClassifierData));
        mStrokeClassifiers.add(new LengthCountClassifier(mClassifierData));
        mStrokeClassifiers.add(new DirectionClassifier(mClassifierData));

        mGestureClassifiers.add(new PointerCountClassifier(mClassifierData));
        mGestureClassifiers.add(new ProximityClassifier(mClassifierData));

        mStrokeClassifiersSize = mStrokeClassifiers.size();
        mGestureClassifiersSize = mGestureClassifiers.size();

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(HIC_ENABLE), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateConfiguration();
    }

    public static HumanInteractionClassifier getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HumanInteractionClassifier(context);
        }
        return sInstance;
    }

    private void updateConfiguration() {
        mEnableClassifier = 0 != Settings.Global.getInt(
                mContext.getContentResolver(),
                HIC_ENABLE, HIC_ENABLE_DEFAULT);
    }

    public void setType(int type) {
        mCurrentType = type;
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (!mEnableClassifier) {
            return;
        }

        // If the user is dragging down the notification, they might want to drag it down
        // enough to see the content, read it for a while and then lift the finger to open
        // the notification. This kind of motion scores very bad in the Classifier so the
        // MotionEvents which are close to the current position of the finger are not
        // sent to the classifiers until the finger moves far enough. When the finger if lifted
        // up, the last MotionEvent which was far enough from the finger is set as the final
        // MotionEvent and sent to the Classifiers.
        if (mCurrentType == Classifier.NOTIFICATION_DRAG_DOWN) {
            mBufferedEvents.add(MotionEvent.obtain(event));
            Point pointEnd = new Point(event.getX() / mDpi, event.getY() / mDpi);

            while (pointEnd.dist(new Point(mBufferedEvents.getFirst().getX() / mDpi,
                    mBufferedEvents.getFirst().getY() / mDpi)) > FINGER_DISTANCE) {
                addTouchEvent(mBufferedEvents.getFirst());
                mBufferedEvents.remove();
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                mBufferedEvents.getFirst().setAction(MotionEvent.ACTION_UP);
                addTouchEvent(mBufferedEvents.getFirst());
                mBufferedEvents.clear();
            }
        } else {
            addTouchEvent(event);
        }
    }

    private void addTouchEvent(MotionEvent event) {
        mClassifierData.update(event);

        for (int i = 0; i < mStrokeClassifiersSize; i++) {
            mStrokeClassifiers.get(i).onTouchEvent(event);
        }

        for (int i = 0; i < mGestureClassifiersSize; i++) {
            mGestureClassifiers.get(i).onTouchEvent(event);
        }

        int size = mClassifierData.getEndingStrokes().size();
        for (int i = 0; i < size; i++) {
            Stroke stroke = mClassifierData.getEndingStrokes().get(i);
            float evaluation = 0.0f;
            for (int j = 0; j < mStrokeClassifiersSize; j++) {
                evaluation += mStrokeClassifiers.get(j).getFalseTouchEvaluation(
                        mCurrentType, stroke);
            }
            mHistoryEvaluator.addStroke(evaluation);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float evaluation = 0.0f;
            for (int i = 0; i < mGestureClassifiersSize; i++) {
                evaluation += mGestureClassifiers.get(i).getFalseTouchEvaluation(mCurrentType);
            }
            mHistoryEvaluator.addGesture(evaluation);
            setType(Classifier.GENERIC);
        }

        mClassifierData.cleanUp(event);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        for (int i = 0; i < mStrokeClassifiers.size(); i++) {
            mStrokeClassifiers.get(i).onSensorChanged(event);
        }

        for (int i = 0; i < mGestureClassifiers.size(); i++) {
            mGestureClassifiers.get(i).onSensorChanged(event);
        }
    }

    public boolean isFalseTouch() {
        if (mEnableClassifier) {
            return mHistoryEvaluator.getEvaluation() >= 5.0f;
        }
        return false;
    }

    public boolean isEnabled() {
        return mEnableClassifier;
    }
}
