/*
 * Copyright (C) 2008 Google Inc.
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

package com.ringdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ImageView;

/**
 * Represents a draggable start or end marker.
 *
 * Most events are passed back to the client class using a
 * listener interface.
 *
 * This class directly keeps track of its own velocity, though,
 * accelerating as the user holds down the left or right arrows
 * while this control is focused.
 */
public class MarkerView extends ImageView {

    public interface MarkerListener {
        public void markerTouchStart(MarkerView marker, float pos);
        public void markerTouchMove(MarkerView marker, float pos);
        public void markerTouchEnd(MarkerView marker);
        public void markerFocus(MarkerView marker);
        public void markerLeft(MarkerView marker, int velocity);
        public void markerRight(MarkerView marker, int velocity);
        public void markerEnter(MarkerView marker);
        public void markerKeyUp();
        public void markerDraw();
    };

    private int velocityAmount;
    private MarkerListener listenerMark;

    public MarkerView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Make sure we get keys
        setFocusable(true);

        velocityAmount = 0;
        listenerMark = null;
    }

    public void setListener(MarkerListener listener) {
        listenerMark = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch(event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            requestFocus();
            // We use raw x because this window itself is going to
            // move, which will screw up the "local" coordinates
            listenerMark.markerTouchStart(this, event.getRawX());
            break;
        case MotionEvent.ACTION_MOVE:
            // We use raw x because this window itself is going to
            // move, which will screw up the "local" coordinates
            listenerMark.markerTouchMove(this, event.getRawX());
            break;
        case MotionEvent.ACTION_UP:
            listenerMark.markerTouchEnd(this);
            break;
        }
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  Rect previouslyFocusedRect) {
        if (gainFocus && listenerMark != null)
            listenerMark.markerFocus(this);
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (listenerMark != null)
            listenerMark.markerDraw();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        velocityAmount++;
        int v = (int)Math.sqrt(1 + velocityAmount / 2);
        if (listenerMark != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                listenerMark.markerLeft(this, v);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                listenerMark.markerRight(this, v);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                listenerMark.markerEnter(this);
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        velocityAmount = 0;
        if (listenerMark != null)
            listenerMark.markerKeyUp();
        return super.onKeyDown(keyCode, event);
    }
}
