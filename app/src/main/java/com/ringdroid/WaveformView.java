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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.ringdroid.soundfile.SoundFile;

/**
 * WaveformView is an Android view that displays a visual representation
 * of an audio waveform.  It retrieves the frame gains from a CheapSoundFile
 * object and recomputes the shape contour at several zoom levels.
 *
 * This class doesn't handle selection or any of the touch interactions
 * directly, so it exposes a listener interface.  The class that embeds
 * this view should add itself as a listener and make the view scroll
 * and respond to other events appropriately.
 *
 * WaveformView doesn't actually handle selection, but it will just display
 * the selected part of the waveform in a different color.
 */
public class WaveformView extends View {
    public interface WaveformListener {
        public void waveformTouchStart(float x);
        public void waveformTouchMove(float x);
        public void waveformTouchEnd();
        public void waveformFling(float x);
        public void waveformDraw();
        public void waveformZoomIn();
        public void waveformZoomOut();
    };

    // Colors
    private Paint gridPaint;
    private Paint selectedLinePaint;
    private Paint unselectedLinePaint;
    private Paint unselectedBkgndLinePaint;
    private Paint borderLinePaint;
    private Paint playbackLinePaint;
    private Paint timecodePaint;

    private SoundFile soundFile;
    private int[] lenByZoomLevel;
    private double[][] valuesByZoomLevel;
    private double[] zoomFactorByZoomLevel;
    private int[] heightsAtThisZoomLevel;
    private int zoomLevel;
    private int numZoomLevels;
    private int sampleRate;
    private int samplesPerFrame;
    private int offset;
    private int selectionStart;
    private int selectionEnd;
    private int playbackPos;
    private float density;
    private float initialScaleSpan;
    private WaveformListener listener;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean initialized;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // We don't want keys, the markers get these
        setFocusable(false);

        Resources res = getResources();
        gridPaint = new Paint();
        gridPaint.setAntiAlias(false);
        gridPaint.setColor(res.getColor(R.color.grid_line));
        selectedLinePaint = new Paint();
        selectedLinePaint.setAntiAlias(false);
        selectedLinePaint.setColor(res.getColor(R.color.waveform_selected));
        unselectedLinePaint = new Paint();
        unselectedLinePaint.setAntiAlias(false);
        unselectedLinePaint.setColor(res.getColor(R.color.waveform_unselected));
        unselectedBkgndLinePaint = new Paint();
        unselectedBkgndLinePaint.setAntiAlias(false);
        unselectedBkgndLinePaint.setColor(res.getColor(R.color.waveform_unselected_bkgnd_overlay));
        borderLinePaint = new Paint();
        borderLinePaint.setAntiAlias(true);
        borderLinePaint.setStrokeWidth(1.5f);
        borderLinePaint.setPathEffect(new DashPathEffect(new float[] { 3.0f, 2.0f }, 0.0f));
        borderLinePaint.setColor(res.getColor(R.color.selection_border));
        playbackLinePaint = new Paint();
        playbackLinePaint.setAntiAlias(false);
        playbackLinePaint.setColor(res.getColor(R.color.playback_indicator));
        timecodePaint = new Paint();
        timecodePaint.setTextSize(12);
        timecodePaint.setAntiAlias(true);
        timecodePaint.setColor(res.getColor(R.color.timecode));
        timecodePaint.setShadowLayer(2, 1, 1, res.getColor(R.color.timecode_shadow));

        gestureDetector = new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    listener.waveformFling(vx);
                    return true;
                }
            }
        );

        scaleGestureDetector = new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScaleBegin(ScaleGestureDetector d) {
                    Log.i("WaveformView", "onScaleBegin() " + d.getCurrentSpanX());
                    initialScaleSpan = Math.abs(d.getCurrentSpanX());
                    return true;
                }
                public boolean onScale(ScaleGestureDetector d) {
                    float scale = Math.abs(d.getCurrentSpanX());
                    Log.i("WaveformView", "onScale() " + (scale - initialScaleSpan));
                    if (scale - initialScaleSpan > 40) {
                        listener.waveformZoomIn();
                        initialScaleSpan = scale;
                    }
                    if (scale - initialScaleSpan < -40) {
                        listener.waveformZoomOut();
                        initialScaleSpan = scale;
                    }
                    return true;
                }
                public void onScaleEnd(ScaleGestureDetector d) {
                    Log.i("WaveformView", "onScaleEnd() " + d.getCurrentSpanX());
                }
            }
        );

        soundFile = null;
        lenByZoomLevel = null;
        valuesByZoomLevel = null;
        heightsAtThisZoomLevel = null;
        offset = 0;
        playbackPos = -1;
        selectionStart = 0;
        selectionEnd = 0;
        density = 1.0f;
        initialized = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        if (gestureDetector.onTouchEvent(event)) {
            return true;
        }

        switch(event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            listener.waveformTouchStart(event.getX());
            break;
        case MotionEvent.ACTION_MOVE:
            listener.waveformTouchMove(event.getX());
            break;
        case MotionEvent.ACTION_UP:
            listener.waveformTouchEnd();
            break;
        }
        return true;
    }

    public boolean hasSoundFile() {
        return soundFile != null;
    }

    public void setSoundFile(SoundFile soundFile) {
        this.soundFile = soundFile;
        sampleRate = this.soundFile.getSampleRate();
        samplesPerFrame = this.soundFile.getSamplesPerFrame();
        computeDoublesForAllZoomLevels();
        heightsAtThisZoomLevel = null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getZoomLevel() {
        return zoomLevel;
    }

    public void setZoomLevel(int zoomLevel) {
        while (this.zoomLevel > zoomLevel) {
            zoomIn();
        }
        while (this.zoomLevel < zoomLevel) {
            zoomOut();
        }
    }

    public boolean canZoomIn() {
        return (zoomLevel > 0);
    }

    public void zoomIn() {
        if (canZoomIn()) {
            zoomLevel--;
            selectionStart *= 2;
            selectionEnd *= 2;
            heightsAtThisZoomLevel = null;
            int offsetCenter = offset + getMeasuredWidth() / 2;
            offsetCenter *= 2;
            offset = offsetCenter - getMeasuredWidth() / 2;
            if (offset < 0)
                offset = 0;
            invalidate();
        }
    }

    public boolean canZoomOut() {
        return (zoomLevel < numZoomLevels - 1);
    }

    public void zoomOut() {
        if (canZoomOut()) {
            zoomLevel++;
            selectionStart /= 2;
            selectionEnd /= 2;
            int offsetCenter = offset + getMeasuredWidth() / 2;
            offsetCenter /= 2;
            offset = offsetCenter - getMeasuredWidth() / 2;
            if (offset < 0)
                offset = 0;
            heightsAtThisZoomLevel = null;
            invalidate();
        }
    }

    public int maxPos() {
        return lenByZoomLevel[zoomLevel];
    }

    public int secondsToFrames(double seconds) {
        return (int)(1.0 * seconds * sampleRate / samplesPerFrame + 0.5);
    }

    public int secondsToPixels(double seconds) {
        double z = zoomFactorByZoomLevel[zoomLevel];
        return (int)(z * seconds * sampleRate / samplesPerFrame + 0.5);
    }

    public double pixelsToSeconds(int pixels) {
        double z = zoomFactorByZoomLevel[zoomLevel];
        return (pixels * (double) samplesPerFrame / (sampleRate * z));
    }

    public int millisecsToPixels(int msecs) {
        double z = zoomFactorByZoomLevel[zoomLevel];
        return (int)((msecs * 1.0 * sampleRate * z) /
                     (1000.0 * samplesPerFrame) + 0.5);
    }

    public int pixelsToMillisecs(int pixels) {
        double z = zoomFactorByZoomLevel[zoomLevel];
        return (int)(pixels * (1000.0 * samplesPerFrame) /
                     (sampleRate * z) + 0.5);
    }

    public void setParameters(int start, int end, int offset) {
        selectionStart = start;
        selectionEnd = end;
        this.offset = offset;
    }

    public int getStart() {
        return selectionStart;
    }

    public int getEnd() {
        return selectionEnd;
    }

    public int getOffset() {
        return offset;
    }

    public void setPlayback(int pos) {
        playbackPos = pos;
    }

    public void setListener(WaveformListener listener) {
        this.listener = listener;
    }

    public void recomputeHeights(float density) {
        heightsAtThisZoomLevel = null;
        this.density = density;
        timecodePaint.setTextSize((int)(12 * density));

        invalidate();
    }

    protected void drawWaveformLine(Canvas canvas,
                                    int x, int y0, int y1,
                                    Paint paint) {
        canvas.drawLine(x, y0, x, y1, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (soundFile == null)
            return;

        if (heightsAtThisZoomLevel == null)
            computeIntsForThisZoomLevel();

        // Draw waveform
        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        int start = offset;
        int width = heightsAtThisZoomLevel.length - start;
        int ctr = measuredHeight / 2;

        if (width > measuredWidth)
            width = measuredWidth;

        // Draw grid
        double onePixelInSecs = pixelsToSeconds(1);
        boolean onlyEveryFiveSecs = (onePixelInSecs > 1.0 / 50.0);
        double fractionalSecs = offset * onePixelInSecs;
        int integerSecs = (int) fractionalSecs;
        int i = 0;
        while (i < width) {
            i++;
            fractionalSecs += onePixelInSecs;
            int integerSecsNew = (int) fractionalSecs;
            if (integerSecsNew != integerSecs) {
                integerSecs = integerSecsNew;
                if (!onlyEveryFiveSecs || 0 == (integerSecs % 5)) {
                    canvas.drawLine(i, 0, i, measuredHeight, gridPaint);
                }
            }
        }

        // Draw waveform
        for (i = 0; i < width; i++) {
            Paint paint;
            if (i + start >= selectionStart &&
                i + start < selectionEnd) {
                paint = selectedLinePaint;
            } else {
                drawWaveformLine(canvas, i, 0, measuredHeight,
                        unselectedBkgndLinePaint);
                paint = unselectedLinePaint;
            }
            drawWaveformLine(
                canvas, i,
                ctr - heightsAtThisZoomLevel[start + i],
                ctr + 1 + heightsAtThisZoomLevel[start + i],
                paint);

            if (i + start == playbackPos) {
                canvas.drawLine(i, 0, i, measuredHeight, playbackLinePaint);
            }
        }

        // If we can see the right edge of the waveform, draw the
        // non-waveform area to the right as unselected
        for (i = width; i < measuredWidth; i++) {
            drawWaveformLine(canvas, i, 0, measuredHeight,
                    unselectedBkgndLinePaint);
        }

        // Draw borders
        canvas.drawLine(
            selectionStart - offset + 0.5f, 30,
            selectionStart - offset + 0.5f, measuredHeight,
                borderLinePaint);
        canvas.drawLine(
            selectionEnd - offset + 0.5f, 0,
            selectionEnd - offset + 0.5f, measuredHeight - 30,
                borderLinePaint);

        // Draw timecode
        double timecodeIntervalSecs = 1.0;
        if (timecodeIntervalSecs / onePixelInSecs < 50) {
            timecodeIntervalSecs = 5.0;
        }
        if (timecodeIntervalSecs / onePixelInSecs < 50) {
            timecodeIntervalSecs = 15.0;
        }

        // Draw grid
        fractionalSecs = offset * onePixelInSecs;
        int integerTimecode = (int) (fractionalSecs / timecodeIntervalSecs);
        i = 0;
        while (i < width) {
            i++;
            fractionalSecs += onePixelInSecs;
            integerSecs = (int) fractionalSecs;
            int integerTimecodeNew = (int) (fractionalSecs /
                                            timecodeIntervalSecs);
            if (integerTimecodeNew != integerTimecode) {
                integerTimecode = integerTimecodeNew;

                // Turn, e.g. 67 seconds into "1:07"
                String timecodeMinutes = "" + (integerSecs / 60);
                String timecodeSeconds = "" + (integerSecs % 60);
                if ((integerSecs % 60) < 10) {
                    timecodeSeconds = "0" + timecodeSeconds;
                }
                String timecodeStr = timecodeMinutes + ":" + timecodeSeconds;
                float offset = (float) (
                    0.5 * timecodePaint.measureText(timecodeStr));
                canvas.drawText(timecodeStr,
                                i - offset,
                                (int)(12 * density),
                        timecodePaint);
            }
        }

        if (listener != null) {
            listener.waveformDraw();
        }
    }

    /**
     * Called once when a new sound file is added
     */
    private void computeDoublesForAllZoomLevels() {
        int numFrames = soundFile.getNumFrames();
        int[] frameGains = soundFile.getFrameGains();
        double[] smoothedGains = new double[numFrames];
        if (numFrames == 1) {
            smoothedGains[0] = frameGains[0];
        } else if (numFrames == 2) {
            smoothedGains[0] = frameGains[0];
            smoothedGains[1] = frameGains[1];
        } else if (numFrames > 2) {
            smoothedGains[0] = (double)(
                (frameGains[0] / 2.0) +
                (frameGains[1] / 2.0));
            for (int i = 1; i < numFrames - 1; i++) {
                smoothedGains[i] = (double)(
                    (frameGains[i - 1] / 3.0) +
                    (frameGains[i    ] / 3.0) +
                    (frameGains[i + 1] / 3.0));
            }
            smoothedGains[numFrames - 1] = (double)(
                (frameGains[numFrames - 2] / 2.0) +
                (frameGains[numFrames - 1] / 2.0));
        }

        // Make sure the range is no more than 0 - 255
        double maxGain = 1.0;
        for (int i = 0; i < numFrames; i++) {
            if (smoothedGains[i] > maxGain) {
                maxGain = smoothedGains[i];
            }
        }
        double scaleFactor = 1.0;
        if (maxGain > 255.0) {
            scaleFactor = 255 / maxGain;
        }

        // Build histogram of 256 bins and figure out the new scaled max
        maxGain = 0;
        int gainHist[] = new int[256];
        for (int i = 0; i < numFrames; i++) {
            int smoothedGain = (int)(smoothedGains[i] * scaleFactor);
            if (smoothedGain < 0)
                smoothedGain = 0;
            if (smoothedGain > 255)
                smoothedGain = 255;

            if (smoothedGain > maxGain)
                maxGain = smoothedGain;

            gainHist[smoothedGain]++;
        }

        // Re-calibrate the min to be 5%
        double minGain = 0;
        int sum = 0;
        while (minGain < 255 && sum < numFrames / 20) {
            sum += gainHist[(int)minGain];
            minGain++;
        }

        // Re-calibrate the max to be 99%
        sum = 0;
        while (maxGain > 2 && sum < numFrames / 100) {
            sum += gainHist[(int)maxGain];
            maxGain--;
        }

        // Compute the heights
        double[] heights = new double[numFrames];
        double range = maxGain - minGain;
        for (int i = 0; i < numFrames; i++) {
            double value = (smoothedGains[i] * scaleFactor - minGain) / range;
            if (value < 0.0)
                value = 0.0;
            if (value > 1.0)
                value = 1.0;
            heights[i] = value * value;
        }

        numZoomLevels = 5;
        lenByZoomLevel = new int[5];
        zoomFactorByZoomLevel = new double[5];
        valuesByZoomLevel = new double[5][];

        // Level 0 is doubled, with interpolated values
        lenByZoomLevel[0] = numFrames * 2;
        zoomFactorByZoomLevel[0] = 2.0;
        valuesByZoomLevel[0] = new double[lenByZoomLevel[0]];
        if (numFrames > 0) {
            valuesByZoomLevel[0][0] = 0.5 * heights[0];
            valuesByZoomLevel[0][1] = heights[0];
        }
        for (int i = 1; i < numFrames; i++) {
            valuesByZoomLevel[0][2 * i] = 0.5 * (heights[i - 1] + heights[i]);
            valuesByZoomLevel[0][2 * i + 1] = heights[i];
        }

        // Level 1 is normal
        lenByZoomLevel[1] = numFrames;
        valuesByZoomLevel[1] = new double[lenByZoomLevel[1]];
        zoomFactorByZoomLevel[1] = 1.0;
        for (int i = 0; i < lenByZoomLevel[1]; i++) {
            valuesByZoomLevel[1][i] = heights[i];
        }

        // 3 more levels are each halved
        for (int j = 2; j < 5; j++) {
            lenByZoomLevel[j] = lenByZoomLevel[j - 1] / 2;
            valuesByZoomLevel[j] = new double[lenByZoomLevel[j]];
            zoomFactorByZoomLevel[j] = zoomFactorByZoomLevel[j - 1] / 2.0;
            for (int i = 0; i < lenByZoomLevel[j]; i++) {
                valuesByZoomLevel[j][i] =
                    0.5 * (valuesByZoomLevel[j - 1][2 * i] +
                           valuesByZoomLevel[j - 1][2 * i + 1]);
            }
        }

        if (numFrames > 5000) {
            zoomLevel = 3;
        } else if (numFrames > 1000) {
            zoomLevel = 2;
        } else if (numFrames > 300) {
            zoomLevel = 1;
        } else {
            zoomLevel = 0;
        }

        initialized = true;
    }

    /**
     * Called the first time we need to draw when the zoom level has changed
     * or the screen is resized
     */
    private void computeIntsForThisZoomLevel() {
        int halfHeight = (getMeasuredHeight() / 2) - 1;
        heightsAtThisZoomLevel = new int[lenByZoomLevel[zoomLevel]];
        for (int i = 0; i < lenByZoomLevel[zoomLevel]; i++) {
            heightsAtThisZoomLevel[i] =
                (int)(valuesByZoomLevel[zoomLevel][i] * halfHeight);
        }
    }
}
