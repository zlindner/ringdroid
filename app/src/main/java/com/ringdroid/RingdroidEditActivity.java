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

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ringdroid.soundfile.SoundFile;

import java.io.File;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * The activity for the Ringdroid main editor window.  Keeps track of
 * the waveform display, current horizontal offset, marker handles,
 * start / end text boxes, and handles all of the buttons and controls.
 */
public class RingdroidEditActivity extends Activity
    implements MarkerView.MarkerListener,
               WaveformView.WaveformListener
{
    private long loadingLastUpdateTime;
    private boolean loadingKeepGoing;
    private long recordingLastUpdateTime;
    private boolean recordingKeepGoing;
    private double recordingTime;
    private boolean finishActivity;
    private TextView timerTextView;
    private AlertDialog alertDialog;
    private AlertDialog recordConfirmationDialog;
    private ProgressDialog progressDialog;
    private SoundFile soundFile;
    private File file;
    private String filename;
    private String artist;
    private String title;
    private int newFileKind;
    private boolean wasGetContentIntent;
    private WaveformView waveformView;
    private MarkerView startMarker;
    private MarkerView endMarker;
    private TextView startText;
    private TextView endText;
    private TextView info;
    private String infoContent;
    private ImageButton playButton;
    private ImageButton rewindButton;
    private ImageButton ffwdButton;
    private boolean keyDown;
    private String caption = "";
    private int width;
    private int maxPos;
    private int startPos;
    private int endPos;
    private boolean startVisible;
    private boolean endVisible;
    private int lastDisplayedStartPos;
    private int lastDisplayedEndPos;
    private int offset;
    private int offsetGoal;
    private int flingVelocity;
    private int playStartMsec;
    private int playEndMsec;
    private Handler handler;
    private boolean isPlaying;
    private SamplePlayer player;
    private boolean touchDragging;
    private float touchStart;
    private int touchInitialOffset;
    private int touchInitialStartPos;
    private int touchInitialEndPos;
    private long waveformTouchStartMsec;
    private float density;
    private int markerLeftInset;
    private int markerRightInset;
    private int markerTopOffset;
    private int markerBottomOffset;

    private Thread loadSoundFileThread;
    private Thread recordAudioThread;
    private Thread saveSoundFileThread;

    // Result codes
    private static final int REQUEST_CODE_CHOOSE_CONTACT = 1;

    /**
     * This is a special intent action that means "edit a sound file".
     */
    public static final String EDIT = "com.ringdroid.action.EDIT";

    //
    // Public methods and protected overrides
    //

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        Log.v("Ringdroid", "EditActivity OnCreate");
        super.onCreate(icicle);

        player = null;
        isPlaying = false;

        alertDialog = null;
        progressDialog = null;
        recordConfirmationDialog = null;

        loadSoundFileThread = null;
        recordAudioThread = null;
        saveSoundFileThread = null;

        Intent intent = getIntent();

        // If the Ringdroid media select activity was launched via a
        // GET_CONTENT intent, then we shouldn't display a "saved"
        // message when the user saves, we should just return whatever
        // they create.
        wasGetContentIntent = intent.getBooleanExtra("was_get_content_intent", false);

        filename = intent.getData().toString().replaceFirst("file://", "").replaceAll("%20", " ");
        soundFile = null;
        keyDown = false;

        handler = new Handler();

        loadGui();

        handler.postDelayed(timerRunnable, 100);

        if (!filename.equals("record")) {
            loadFromFile();
        } else {
            recordAudio();
        }
    }

    private void closeThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Log.e("RingdroidEditActivity", "closeThread() error closing thread\n" + getStackTrace(e));
            }
        }
    }

    /** Called when the activity is finally destroyed. */
    @Override
    protected void onDestroy() {
        Log.i("RingdroidEditActivity", "onDestroy() called");

        loadingKeepGoing = false;
        recordingKeepGoing = false;
        closeThread(loadSoundFileThread);
        closeThread(recordAudioThread);
        closeThread(saveSoundFileThread);
        loadSoundFileThread = null;
        recordAudioThread = null;
        saveSoundFileThread = null;
        if(progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        if(alertDialog != null) {
            alertDialog.dismiss();
            alertDialog = null;
        }

        if (player != null) {
            if (player.isPlaying() || player.isPaused()) {
                player.stop();
            }
            player.release();
            player = null;
        }

        super.onDestroy();
    }

    /** Called with an Activity we started with an Intent returns. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent dataIntent) {
        Log.i("RingdroidEditActivity", "onActivityResult() called");
        if (requestCode == REQUEST_CODE_CHOOSE_CONTACT) {
            // The user finished saving their ringtone and they're
            // just applying it to a contact.  When they return here,
            // they're done.
            finish();
            return;
        }
    }

    /**
     * Called when the orientation changes and/or the keyboard is shown
     * or hidden.  We don't need to recreate the whole activity in this
     * case, but we do need to redo our layout somewhat.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i("RingdroidEditActivity", "onConfigurationChanged() called");
        final int saveZoomLevel = waveformView.getZoomLevel();
        super.onConfigurationChanged(newConfig);

        loadGui();

        handler.postDelayed(new Runnable() {
                public void run() {
                    startMarker.requestFocus();
                    markerFocus(startMarker);

                    waveformView.setZoomLevel(saveZoomLevel);
                    waveformView.recomputeHeights(density);

                    updateDisplay();
                }
            }, 500);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.edit_options, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.action_save).setVisible(true);
        menu.findItem(R.id.action_reset).setVisible(true);
        menu.findItem(R.id.action_about).setVisible(true);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.action_save:
            onSave();
            return true;
        case R.id.action_reset:
            resetPositions();
            offsetGoal = 0;
            updateDisplay();
            return true;
        case R.id.action_about:
            onAbout(this);
            return true;
        case R.id.action_help:
            onHelp(this);
            return true;
        default:
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            onPlay(startPos);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    //
    // WaveformListener
    //

    /**
     * Every time we get a message that our waveform drew, see if we need to
     * animate and trigger another redraw.
     */
    public void waveformDraw() {
        width = waveformView.getMeasuredWidth();
        if (offsetGoal != offset && !keyDown)
            updateDisplay();
        else if (isPlaying) {
            updateDisplay();
        } else if (flingVelocity != 0) {
            updateDisplay();
        }
    }

    public void waveformTouchStart(float x) {
        touchDragging = true;
        touchStart = x;
        touchInitialOffset = offset;
        flingVelocity = 0;
        waveformTouchStartMsec = getCurrentTime();
    }

    public void waveformTouchMove(float x) {
        offset = trap((int)(touchInitialOffset + (touchStart - x)));
        updateDisplay();
    }

    public void waveformTouchEnd() {
        touchDragging = false;
        offsetGoal = offset;

        long elapsedMsec = getCurrentTime() - waveformTouchStartMsec;
        if (elapsedMsec < 300) {
            if (isPlaying) {
                int seekMsec = waveformView.pixelsToMillisecs(
                    (int)(touchStart + offset));
                if (seekMsec >= playStartMsec &&
                    seekMsec < playEndMsec) {
                    player.seekTo(seekMsec);
                } else {
                    handlePause();
                }
            } else {
                onPlay((int)(touchStart + offset));
            }
        }
    }

    public void waveformFling(float vx) {
        touchDragging = false;
        offsetGoal = offset;
        flingVelocity = (int)(-vx);
        updateDisplay();
    }

    public void waveformZoomIn() {
        waveformView.zoomIn();
        startPos = waveformView.getStart();
        endPos = waveformView.getEnd();
        maxPos = waveformView.maxPos();
        offset = waveformView.getOffset();
        offsetGoal = offset;
        updateDisplay();
    }

    public void waveformZoomOut() {
        waveformView.zoomOut();
        startPos = waveformView.getStart();
        endPos = waveformView.getEnd();
        maxPos = waveformView.maxPos();
        offset = waveformView.getOffset();
        offsetGoal = offset;
        updateDisplay();
    }

    //
    // MarkerListener
    //

    public void markerDraw() {
    }

    public void markerTouchStart(MarkerView marker, float x) {
        touchDragging = true;
        touchStart = x;
        touchInitialStartPos = startPos;
        touchInitialEndPos = endPos;
    }

    public void markerTouchMove(MarkerView marker, float x) {
        float delta = x - touchStart;

        if (marker == startMarker) {
            startPos = trap((int)(touchInitialStartPos + delta));
            endPos = trap((int)(touchInitialEndPos + delta));
        } else {
            endPos = trap((int)(touchInitialEndPos + delta));
            if (endPos < startPos)
                endPos = startPos;
        }

        updateDisplay();
    }

    public void markerTouchEnd(MarkerView marker) {
        touchDragging = false;
        if (marker == startMarker) {
            setOffsetGoalStart();
        } else {
            setOffsetGoalEnd();
        }
    }

    public void markerLeft(MarkerView marker, int velocity) {
        keyDown = true;

        if (marker == startMarker) {
            int saveStart = startPos;
            startPos = trap(startPos - velocity);
            endPos = trap(endPos - (saveStart - startPos));
            setOffsetGoalStart();
        }

        if (marker == endMarker) {
            if (endPos == startPos) {
                startPos = trap(startPos - velocity);
                endPos = startPos;
            } else {
                endPos = trap(endPos - velocity);
            }

            setOffsetGoalEnd();
        }

        updateDisplay();
    }

    public void markerRight(MarkerView marker, int velocity) {
        keyDown = true;

        if (marker == startMarker) {
            int saveStart = startPos;
            startPos += velocity;
            if (startPos > maxPos)
                startPos = maxPos;
            endPos += (startPos - saveStart);
            if (endPos > maxPos)
                endPos = maxPos;

            setOffsetGoalStart();
        }

        if (marker == endMarker) {
            endPos += velocity;
            if (endPos > maxPos)
                endPos = maxPos;

            setOffsetGoalEnd();
        }

        updateDisplay();
    }

    public void markerEnter(MarkerView marker) {
    }

    public void markerKeyUp() {
        keyDown = false;
        updateDisplay();
    }

    public void markerFocus(MarkerView marker) {
        keyDown = false;
        if (marker == startMarker) {
            setOffsetGoalStartNoUpdate();
        } else {
            setOffsetGoalEndNoUpdate();
        }

        // Delay updaing the display because if this focus was in
        // response to a touch event, we want to receive the touch
        // event too before updating the display.
        handler.postDelayed(new Runnable() {
                public void run() {
                    updateDisplay();
                }
            }, 100);
    }

    //
    // Static About dialog method, also called from RingdroidSelectActivity
    //

    public static void onAbout(final Activity activity) {
        String versionName = "";
        try {
            PackageManager packageManager = activity.getPackageManager();
            String packageName = activity.getPackageName();
            versionName = packageManager.getPackageInfo(packageName, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            versionName = "unknown";
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setMessage(activity.getString(R.string.about_text, versionName))
            .setPositiveButton(R.string.alert_ok_button, null)
            .setCancelable(false)
            .show();
    }

    private void onHelp(Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.help_title)
            .setMessage(R.string.edit_activity_help)
            .setPositiveButton(R.string.alert_ok_button, null)
            .setCancelable(false)
            .show();
    }

    //
    // Internal methods
    //

    /**
     * Called from both onCreate and onConfigurationChanged
     * (if the user switched layouts)
     */
    private void loadGui() {
        // Inflate our UI from its XML layout description.
        setContentView(R.layout.editor);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        density = metrics.density;

        markerLeftInset = (int)(46 * density);
        markerRightInset = (int)(48 * density);
        markerTopOffset = (int)(10 * density);
        markerBottomOffset = (int)(10 * density);

        startText = (TextView)findViewById(R.id.starttext);
        startText.addTextChangedListener(textWatcher);
        endText = (TextView)findViewById(R.id.endtext);
        endText.addTextChangedListener(textWatcher);

        playButton = (ImageButton)findViewById(R.id.play);
        playButton.setOnClickListener(playListener);
        rewindButton = (ImageButton)findViewById(R.id.rew);
        rewindButton.setOnClickListener(rewindListener);
        ffwdButton = (ImageButton)findViewById(R.id.ffwd);
        ffwdButton.setOnClickListener(ffwdListener);

        TextView markStartButton = (TextView) findViewById(R.id.mark_start);
        markStartButton.setOnClickListener(markStartListener);
        TextView markEndButton = (TextView) findViewById(R.id.mark_end);
        markEndButton.setOnClickListener(markEndListener);

        enableDisableButtons();

        waveformView = (WaveformView)findViewById(R.id.waveform);
        waveformView.setListener(this);

        info = (TextView)findViewById(R.id.info);
        info.setText(caption);

        maxPos = 0;
        lastDisplayedStartPos = -1;
        lastDisplayedEndPos = -1;

        if (soundFile != null && !waveformView.hasSoundFile()) {
            waveformView.setSoundFile(soundFile);
            waveformView.recomputeHeights(density);
            maxPos = waveformView.maxPos();
        }

        startMarker = (MarkerView)findViewById(R.id.startmarker);
        startMarker.setListener(this);
        startMarker.setAlpha(1f);
        startMarker.setFocusable(true);
        startMarker.setFocusableInTouchMode(true);
        startVisible = true;

        endMarker = (MarkerView)findViewById(R.id.endmarker);
        endMarker.setListener(this);
        endMarker.setAlpha(1f);
        endMarker.setFocusable(true);
        endMarker.setFocusableInTouchMode(true);
        endVisible = true;

        updateDisplay();
    }

    private void loadFromFile() {
        file = new File(filename);

        SongMetadataReader metadataReader = new SongMetadataReader(
            this, filename);
        title = metadataReader.songTitle;
        artist = metadataReader.artistName;

        String titleLabel = title;
        if (artist != null && artist.length() > 0) {
            titleLabel += " - " + artist;
        }
        setTitle(titleLabel);

        loadingLastUpdateTime = getCurrentTime();
        loadingKeepGoing = true;
        finishActivity = false;
        progressDialog = new ProgressDialog(RingdroidEditActivity.this);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setTitle(R.string.progress_dialog_loading);
        progressDialog.setCancelable(true);
        progressDialog.setOnCancelListener(
            new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    loadingKeepGoing = false;
                    finishActivity = true;
                }
            });
        progressDialog.show();

        final SoundFile.ProgressListener listener =
            new SoundFile.ProgressListener() {
                public boolean reportProgress(double fractionComplete) {
                    long now = getCurrentTime();
                    if (now - loadingLastUpdateTime > 100) {
                        progressDialog.setProgress(
                                (int) (progressDialog.getMax() * fractionComplete));
                        loadingLastUpdateTime = now;
                    }
                    return loadingKeepGoing;
                }
            };

        // Load the sound file in a background thread
        loadSoundFileThread = new Thread() {
            public void run() {
                try {
                    soundFile = SoundFile.create(file.getAbsolutePath(), listener);

                    if (soundFile == null) {
                        progressDialog.dismiss();
                        String name = file.getName().toLowerCase();
                        String[] components = name.split("\\.");
                        String err;
                        if (components.length < 2) {
                            err = getResources().getString(
                                R.string.no_extension_error);
                        } else {
                            err = getResources().getString(
                                R.string.bad_extension_error) + " " +
                                components[components.length - 1];
                        }
                        final String finalErr = err;
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(new Exception(), finalErr);
                            }
                        };
                        handler.post(runnable);
                        return;
                    }
                    player = new SamplePlayer(soundFile);
                } catch (final Exception e) {
                    progressDialog.dismiss();
                    infoContent = e.toString();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            info.setText(infoContent);
                        }
                    });

                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(e, getResources().getText(R.string.read_error));
                        }
                    };
                    handler.post(runnable);
                    return;
                }
                progressDialog.dismiss();
                if (loadingKeepGoing) {
                    Runnable runnable = new Runnable() {
                        public void run() {
                            finishOpeningSoundFile();
                        }
                    };
                    handler.post(runnable);
                } else if (finishActivity){
                    RingdroidEditActivity.this.finish();
                }
            }
        };
        loadSoundFileThread.start();
    }

    private void recordAudio() {

        AlertDialog.Builder confirmDialogBuilder = new AlertDialog.Builder(RingdroidEditActivity.this);
        confirmDialogBuilder.setTitle(getResources().getText(R.string.start_label));
        confirmDialogBuilder.setCancelable(true);
        confirmDialogBuilder.setNegativeButton(
                getResources().getText(R.string.progress_dialog_cancel),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        recordingKeepGoing = false;
                        RingdroidEditActivity.this.finish();
                    }
                });
        confirmDialogBuilder.setPositiveButton(
                getResources().getText(R.string.start_label),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        startRecording();
                    }
                });
        confirmDialogBuilder.setView(getLayoutInflater().inflate(R.layout.record_audio, null));
        recordConfirmationDialog = confirmDialogBuilder.show();

    }

    private void startRecording() {
        file = null;
        title = null;
        artist = null;

        recordingLastUpdateTime = getCurrentTime();
        recordingKeepGoing = true;
        finishActivity = false;
        AlertDialog.Builder adBuilder = new AlertDialog.Builder(RingdroidEditActivity.this);
        adBuilder.setTitle(getResources().getText(R.string.progress_dialog_recording));
        adBuilder.setCancelable(true);
        adBuilder.setNegativeButton(
                getResources().getText(R.string.progress_dialog_cancel),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        recordingKeepGoing = false;
                        finishActivity = true;
                    }
                });
        adBuilder.setPositiveButton(
                getResources().getText(R.string.progress_dialog_stop),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        recordingKeepGoing = false;
                    }
                });
        // TODO(nfaralli): try to use a FrameLayout and pass it to the following inflate call.
        // Using null, android:layout_width etc. may not work (hence text is at the top of view).
        // On the other hand, if the text is big enough, this is good enough.
        adBuilder.setView(getLayoutInflater().inflate(R.layout.record_audio, null));
        alertDialog = adBuilder.show();
        timerTextView = (TextView) alertDialog.findViewById(R.id.record_audio_timer);

        final SoundFile.ProgressListener listener =
                new SoundFile.ProgressListener() {
                    public boolean reportProgress(double elapsedTime) {
                        long now = getCurrentTime();
                        if (now - recordingLastUpdateTime > 5) {
                            recordingTime = elapsedTime;
                            // Only UI thread can update Views such as TextViews.
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    int min = (int)(recordingTime /60);
                                    float sec = (float)(recordingTime - 60 * min);
                                    timerTextView.setText(String.format("%d:%05.2f", min, sec));
                                }
                            });
                            recordingLastUpdateTime = now;
                        }
                        return recordingKeepGoing;
                    }
                };

        // Record the audio stream in a background thread
        recordAudioThread = new Thread() {
            public void run() {
                try {
                    soundFile = SoundFile.record(listener);
                    if (soundFile == null) {
                        alertDialog.dismiss();
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(
                                        new Exception(),
                                        getResources().getText(R.string.record_error)
                                );
                            }
                        };
                        handler.post(runnable);
                        return;
                    }
                    player = new SamplePlayer(soundFile);
                } catch (final Exception e) {
                    alertDialog.dismiss();
                    infoContent = e.toString();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            info.setText(infoContent);
                        }
                    });

                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(e, getResources().getText(R.string.record_error));
                        }
                    };
                    handler.post(runnable);
                    return;
                }
                alertDialog.dismiss();
                if (finishActivity){
                    RingdroidEditActivity.this.finish();
                } else {
                    Runnable runnable = new Runnable() {
                        public void run() {
                            finishOpeningSoundFile();
                        }
                    };
                    handler.post(runnable);
                }
            }
        };
        recordAudioThread.start();
    }

    private void finishOpeningSoundFile() {
        waveformView.setSoundFile(soundFile);
        waveformView.recomputeHeights(density);

        maxPos = waveformView.maxPos();
        lastDisplayedStartPos = -1;
        lastDisplayedEndPos = -1;

        touchDragging = false;

        offset = 0;
        offsetGoal = 0;
        flingVelocity = 0;
        resetPositions();
        if (endPos > maxPos)
            endPos = maxPos;

        caption =
            soundFile.getFiletype() + ", " +
            soundFile.getSampleRate() + " Hz, " +
            soundFile.getAvgBitrateKbps() + " kbps, " +
            formatTime(maxPos) + " " +
            getResources().getString(R.string.time_seconds);
        info.setText(caption);

        updateDisplay();
    }

    private synchronized void updateDisplay() {
        if (isPlaying) {
            int now = player.getCurrentPosition();
            int frames = waveformView.millisecsToPixels(now);
            waveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - width / 2);
            if (now >= playEndMsec) {
                handlePause();
            }
        }

        if (!touchDragging) {
            int offsetDelta;

            if (flingVelocity != 0) {
                offsetDelta = flingVelocity / 30;
                if (flingVelocity > 80) {
                    flingVelocity -= 80;
                } else if (flingVelocity < -80) {
                    flingVelocity += 80;
                } else {
                    flingVelocity = 0;
                }

                offset += offsetDelta;

                if (offset + width / 2 > maxPos) {
                    offset = maxPos - width / 2;
                    flingVelocity = 0;
                }
                if (offset < 0) {
                    offset = 0;
                    flingVelocity = 0;
                }
                offsetGoal = offset;
            } else {
                offsetDelta = offsetGoal - offset;

                if (offsetDelta > 10)
                    offsetDelta = offsetDelta / 10;
                else if (offsetDelta > 0)
                    offsetDelta = 1;
                else if (offsetDelta < -10)
                    offsetDelta = offsetDelta / 10;
                else if (offsetDelta < 0)
                    offsetDelta = -1;
                else
                    offsetDelta = 0;

                offset += offsetDelta;
            }
        }

        waveformView.setParameters(startPos, endPos, offset);
        waveformView.invalidate();

        startMarker.setContentDescription(
            getResources().getText(R.string.start_marker) + " " +
            formatTime(startPos));
        endMarker.setContentDescription(
            getResources().getText(R.string.end_marker) + " " +
            formatTime(endPos));

        int startX = startPos - offset - markerLeftInset;
        if (startX + startMarker.getWidth() >= 0) {
            if (!startVisible) {
                // Delay this to avoid flicker
                handler.postDelayed(new Runnable() {
                    public void run() {
                        startVisible = true;
                        startMarker.setAlpha(1f);
                    }
                }, 0);
            }
        } else {
            if (startVisible) {
                startMarker.setAlpha(0f);
                startVisible = false;
            }
            startX = 0;
        }

        int endX = endPos - offset - endMarker.getWidth() + markerRightInset;
        if (endX + endMarker.getWidth() >= 0) {
            if (!endVisible) {
                // Delay this to avoid flicker
                handler.postDelayed(new Runnable() {
                    public void run() {
                        endVisible = true;
                        endMarker.setAlpha(1f);
                    }
                }, 0);
            }
        } else {
            if (endVisible) {
                endMarker.setAlpha(0f);
                endVisible = false;
            }
            endX = 0;
        }

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(
            startX,
                markerTopOffset,
            -startMarker.getWidth(),
            -startMarker.getHeight());
        startMarker.setLayoutParams(params);

        params = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(
            endX,
            waveformView.getMeasuredHeight() - endMarker.getHeight() - markerBottomOffset,
            -startMarker.getWidth(),
            -startMarker.getHeight());
        endMarker.setLayoutParams(params);
    }

    private Runnable timerRunnable = new Runnable() {
            public void run() {
                // Updating an EditText is slow on Android.  Make sure
                // we only do the update if the text has actually changed.
                if (startPos != lastDisplayedStartPos &&
                    !startText.hasFocus()) {
                    startText.setText(formatTime(startPos));
                    lastDisplayedStartPos = startPos;
                }

                if (endPos != lastDisplayedEndPos &&
                    !endText.hasFocus()) {
                    endText.setText(formatTime(endPos));
                    lastDisplayedEndPos = endPos;
                }

                handler.postDelayed(timerRunnable, 100);
            }
        };

    private void enableDisableButtons() {
        if (isPlaying) {
            playButton.setImageResource(android.R.drawable.ic_media_pause);
            playButton.setContentDescription(getResources().getText(R.string.stop));
        } else {
            playButton.setImageResource(android.R.drawable.ic_media_play);
            playButton.setContentDescription(getResources().getText(R.string.play));
        }
    }

    private void resetPositions() {
        startPos = waveformView.secondsToPixels(0.0);
        endPos = waveformView.secondsToPixels(15.0);
    }

    private int trap(int pos) {
        if (pos < 0)
            return 0;
        if (pos > maxPos)
            return maxPos;
        return pos;
    }

    private void setOffsetGoalStart() {
        setOffsetGoal(startPos - width / 2);
    }

    private void setOffsetGoalStartNoUpdate() {
        setOffsetGoalNoUpdate(startPos - width / 2);
    }

    private void setOffsetGoalEnd() {
        setOffsetGoal(endPos - width / 2);
    }

    private void setOffsetGoalEndNoUpdate() {
        setOffsetGoalNoUpdate(endPos - width / 2);
    }

    private void setOffsetGoal(int offset) {
        setOffsetGoalNoUpdate(offset);
        updateDisplay();
    }

    private void setOffsetGoalNoUpdate(int offset) {
        if (touchDragging) {
            return;
        }

        offsetGoal = offset;
        if (offsetGoal + width / 2 > maxPos)
            offsetGoal = maxPos - width / 2;
        if (offsetGoal < 0)
            offsetGoal = 0;
    }

    private String formatTime(int pixels) {
        if (waveformView != null && waveformView.isInitialized()) {
            return formatDecimal(waveformView.pixelsToSeconds(pixels));
        } else {
            return "";
        }
    }

    private String formatDecimal(double x) {
        int xWhole = (int)x;
        int xFrac = (int)(100 * (x - xWhole) + 0.5);

        if (xFrac >= 100) {
            xWhole++; //Round up
            xFrac -= 100; //Now we need the remainder after the round up
            if (xFrac < 10) {
                xFrac *= 10; //we need a fraction that is 2 digits long
            }
        }

        if (xFrac < 10)
            return xWhole + ".0" + xFrac;
        else
            return xWhole + "." + xFrac;
    }

    private synchronized void handlePause() {
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        waveformView.setPlayback(-1);
        isPlaying = false;
        enableDisableButtons();
    }

    private synchronized void onPlay(int startPosition) {
        if (isPlaying) {
            handlePause();
            return;
        }

        if (player == null) {
            Log.i("RingdroidEditActivity", "onPlay() player hasn't been initialized yet");
            return;
        }

        try {
            playStartMsec = waveformView.pixelsToMillisecs(startPosition);
            if (startPosition < startPos) {
                playEndMsec = waveformView.pixelsToMillisecs(startPos);
            } else if (startPosition > endPos) {
                playEndMsec = waveformView.pixelsToMillisecs(maxPos);
            } else {
                playEndMsec = waveformView.pixelsToMillisecs(endPos);
            }
            player.setOnCompletionListener(new SamplePlayer.OnCompletionListener() {
                @Override
                public void onCompletion() {
                    handlePause();
                }
            });
            isPlaying = true;

            player.seekTo(playStartMsec);
            player.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            showFinalAlert(e, R.string.play_error);
            return;
        }
    }

    /**
     * Show a "final" alert dialog that will exit the activity
     * after the user clicks on the OK button.  If an exception
     * is passed, it's assumed to be an error condition, and the
     * dialog is presented as an error, and the stack trace is
     * logged.  If there's no exception, it's a success message.
     */
    private void showFinalAlert(Exception e, CharSequence message) {
        CharSequence title;
        if (e != null) {
            Log.e("RingdroidEditActivity", "showFinalAlert() error " + message + "\n" + getStackTrace(e));
            title = getResources().getText(R.string.alert_title_failure);
            setResult(RESULT_CANCELED, new Intent());
        } else {
            Log.i("RingdroidEditActivity", "showFinalAlert() success " + message);
            title = getResources().getText(R.string.alert_title_success);
        }

        new AlertDialog.Builder(RingdroidEditActivity.this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(
                R.string.alert_ok_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog,
                                        int whichButton) {
                        finish();
                    }
                })
            .setCancelable(false)
            .show();
    }

    private void showFinalAlert(Exception e, int messageResourceId) {
        showFinalAlert(e, getResources().getText(messageResourceId));
    }

    private String makeRingtoneFilename(CharSequence title, String extension) {
        String subdir;
        String externalRootDir = Environment.getExternalStorageDirectory().getPath();
        if (!externalRootDir.endsWith("/")) {
            externalRootDir += "/";
        }
        switch(newFileKind) {
        default:
        case FileSaveDialog.FILE_KIND_MUSIC:
            // TODO(nfaralli): can directly use Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getPath() instead
            subdir = "media/audio/music/";
            break;
        case FileSaveDialog.FILE_KIND_ALARM:
            subdir = "media/audio/alarms/";
            break;
        case FileSaveDialog.FILE_KIND_NOTIFICATION:
            subdir = "media/audio/notifications/";
            break;
        case FileSaveDialog.FILE_KIND_RINGTONE:
            subdir = "media/audio/ringtones/";
            break;
        }
        String parentdir = externalRootDir + subdir;

        // Create the parent directory
        File parentDirFile = new File(parentdir);
        parentDirFile.mkdirs();

        // If we can't write to that special path, try just writing
        // directly to the sdcard
        if (!parentDirFile.isDirectory()) {
            parentdir = externalRootDir;
        }

        // Turn the title into a filename
        String filename = "";
        for (int i = 0; i < title.length(); i++) {
            if (Character.isLetterOrDigit(title.charAt(i))) {
                filename += title.charAt(i);
            }
        }

        // Try to make the filename unique
        String path = null;
        for (int i = 0; i < 100; i++) {
            String testPath;
            if (i > 0)
                testPath = parentdir + filename + i + extension;
            else
                testPath = parentdir + filename + extension;

            try {
                RandomAccessFile f = new RandomAccessFile(new File(testPath), "r");
                f.close();
            } catch (Exception e) {
                // Good, the file didn't exist
                path = testPath;
                break;
            }
        }

        return path;
    }

    private void saveRingtone(final CharSequence title) {
        double startTime = waveformView.pixelsToSeconds(startPos);
        double endTime = waveformView.pixelsToSeconds(endPos);
        final int startFrame = waveformView.secondsToFrames(startTime);
        final int endFrame = waveformView.secondsToFrames(endTime);
        final int duration = (int)(endTime - startTime + 0.5);

        // Create an indeterminate progress dialog
        progressDialog = new ProgressDialog(this);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setTitle(R.string.progress_dialog_saving);
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Save the sound file in a background thread
        saveSoundFileThread = new Thread() {
            public void run() {
                // Try AAC first.
                String outPath = makeRingtoneFilename(title, ".m4a");
                if (outPath == null) {
                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(new Exception(), R.string.no_unique_filename);
                        }
                    };
                    handler.post(runnable);
                    return;
                }
                File outFile = new File(outPath);
                boolean fallbackToWAV = false;
                try {
                    // Write the new file
                    soundFile.WriteFile(outFile,  startFrame, endFrame - startFrame);
                } catch (Exception e) {
                    // log the error and try to create a .wav file instead
                    if (outFile.exists()) {
                        outFile.delete();
                    }
                    StringWriter writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    Log.e("RingdroidEditActivity", "saveRingtone() failed to create " + outPath + "\n" + writer.toString());
                    fallbackToWAV = true;
                }

                // Try to create a .wav file if creating a .m4a file failed.
                if (fallbackToWAV) {
                    outPath = makeRingtoneFilename(title, ".wav");
                    if (outPath == null) {
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(new Exception(), R.string.no_unique_filename);
                            }
                        };
                        handler.post(runnable);
                        return;
                    }
                    outFile = new File(outPath);
                    try {
                        // create the .wav file
                        soundFile.WriteWAVFile(outFile, startFrame, endFrame - startFrame);
                    } catch (Exception e) {
                        // Creating the .wav file also failed. Stop the progress dialog, show an
                        // error message and exit.
                        progressDialog.dismiss();
                        if (outFile.exists()) {
                            outFile.delete();
                        }
                        infoContent = e.toString();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                info.setText(infoContent);
                            }
                        });

                        CharSequence errorMessage;
                        if (e.getMessage() != null
                                && e.getMessage().equals("No space left on device")) {
                            errorMessage = getResources().getText(R.string.no_space_error);
                            e = null;
                        } else {
                            errorMessage = getResources().getText(R.string.write_error);
                        }
                        final CharSequence finalErrorMessage = errorMessage;
                        final Exception finalException = e;
                        Runnable runnable = new Runnable() {
                            public void run() {
                                showFinalAlert(finalException, finalErrorMessage);
                            }
                        };
                        handler.post(runnable);
                        return;
                    }
                }

                // Try to load the new file to make sure it worked
                try {
                    final SoundFile.ProgressListener listener =
                        new SoundFile.ProgressListener() {
                            public boolean reportProgress(double frac) {
                                // Do nothing - we're not going to try to
                                // estimate when reloading a saved sound
                                // since it's usually fast, but hard to
                                // estimate anyway.
                                return true;  // Keep going
                            }
                        };
                    SoundFile.create(outPath, listener);
                } catch (final Exception e) {
                    progressDialog.dismiss();
                    infoContent = e.toString();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            info.setText(infoContent);
                        }
                    });

                    Runnable runnable = new Runnable() {
                        public void run() {
                            showFinalAlert(e, getResources().getText(R.string.write_error));
                        }
                    };
                    handler.post(runnable);
                    return;
                }

                progressDialog.dismiss();

                final String finalOutPath = outPath;
                Runnable runnable = new Runnable() {
                        public void run() {
                            afterSavingRingtone(title,
                                                finalOutPath,
                                                duration);
                        }
                    };
                handler.post(runnable);
            }
        };
        saveSoundFileThread.start();
    }

    private void afterSavingRingtone(CharSequence title,
                                     String outPath,
                                     int duration) {
        File outFile = new File(outPath);
        long fileSize = outFile.length();
        if (fileSize <= 512) {
            outFile.delete();
            new AlertDialog.Builder(this)
                .setTitle(R.string.alert_title_failure)
                .setMessage(R.string.too_small_error)
                .setPositiveButton(R.string.alert_ok_button, null)
                .setCancelable(false)
                .show();
            return;
        }

        // Create the database record, pointing to the existing file path
        String mimeType;
        if (outPath.endsWith(".m4a")) {
            mimeType = "audio/mp4a-latm";
        } else if (outPath.endsWith(".wav")) {
            mimeType = "audio/wav";
        } else {
            // This should never happen.
            mimeType = "audio/mpeg";
        }

        String artist = "" + getResources().getText(R.string.artist_name);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, outPath);
        values.put(MediaStore.MediaColumns.TITLE, title.toString());
        values.put(MediaStore.MediaColumns.SIZE, fileSize);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        values.put(MediaStore.Audio.Media.ARTIST, artist);
        values.put(MediaStore.Audio.Media.DURATION, duration*1000);

        values.put(MediaStore.Audio.Media.IS_RINGTONE,
                   newFileKind == FileSaveDialog.FILE_KIND_RINGTONE);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION,
                   newFileKind == FileSaveDialog.FILE_KIND_NOTIFICATION);
        values.put(MediaStore.Audio.Media.IS_ALARM,
                   newFileKind == FileSaveDialog.FILE_KIND_ALARM);
        values.put(MediaStore.Audio.Media.IS_MUSIC,
                   newFileKind == FileSaveDialog.FILE_KIND_MUSIC);

        // Insert it into the database
        Uri uri = MediaStore.Audio.Media.getContentUriForPath(outPath);
        final Uri newUri = getContentResolver().insert(uri, values);
        setResult(RESULT_OK, new Intent().setData(newUri));

        // If Ringdroid was launched to get content, just return
        if (wasGetContentIntent) {
            finish();
            return;
        }

        // There's nothing more to do with music or an alarm.  Show a
        // success message and then quit.
        if (newFileKind == FileSaveDialog.FILE_KIND_MUSIC ||
            newFileKind == FileSaveDialog.FILE_KIND_ALARM) {
            Toast.makeText(this,
                           R.string.save_success_message,
                           Toast.LENGTH_SHORT)
                .show();
            finish();
            return;
        }

        // If it's a notification, give the user the option of making
        // this their default notification.  If they say no, we're finished.
        if (newFileKind == FileSaveDialog.FILE_KIND_NOTIFICATION) {
            new AlertDialog.Builder(RingdroidEditActivity.this)
                .setTitle(R.string.alert_title_success)
                .setMessage(R.string.set_default_notification)
                .setPositiveButton(R.string.alert_yes_button,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog,
                                            int whichButton) {
                            RingtoneManager.setActualDefaultRingtoneUri(
                                RingdroidEditActivity.this,
                                RingtoneManager.TYPE_NOTIFICATION,
                                newUri);
                            finish();
                        }
                    })
                .setNegativeButton(
                    R.string.alert_no_button,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            finish();
                        }
                    })
                .setCancelable(false)
                .show();
            return;
        }

        // If we get here, that means the type is a ringtone.  There are
        // three choices: make this your default ringtone, assign it to a
        // contact, or do nothing.

        final Handler handler = new Handler() {
                public void handleMessage(Message response) {
                    int actionId = response.arg1;
                    switch (actionId) {
                    case R.id.button_make_default:
                        RingtoneManager.setActualDefaultRingtoneUri(
                            RingdroidEditActivity.this,
                            RingtoneManager.TYPE_RINGTONE,
                            newUri);
                        Toast.makeText(
                            RingdroidEditActivity.this,
                            R.string.default_ringtone_success_message,
                            Toast.LENGTH_SHORT)
                            .show();
                        finish();
                        break;
                    case R.id.button_choose_contact:
                        chooseContactForRingtone(newUri);
                        break;
                    default:
                    case R.id.button_do_nothing:
                        finish();
                        break;
                    }
                }
            };
        Message message = Message.obtain(handler);
        AfterSaveActionDialog dlog = new AfterSaveActionDialog(
            this, message);
        dlog.show();
    }

    private void chooseContactForRingtone(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_EDIT, uri);
            intent.setClassName(
                "com.ringdroid",
                "com.ringdroid.ChooseContactActivity");
            startActivityForResult(intent, REQUEST_CODE_CHOOSE_CONTACT, ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
        } catch (Exception e) {
            Log.e("RingdroidEditActivity", "chooseContactForRingtone() error opening choose contact window\n" + getStackTrace(e));
        }
    }

    private void onSave() {
        if (isPlaying) {
            handlePause();
        }

        final Handler handler = new Handler() {
                public void handleMessage(Message response) {
                    CharSequence newTitle = (CharSequence)response.obj;
                    newFileKind = response.arg1;
                    saveRingtone(newTitle);
                }
            };
        Message message = Message.obtain(handler);
        FileSaveDialog dlog = new FileSaveDialog(
            this, getResources(), title, message);
        dlog.show();
    }

    private OnClickListener playListener = new OnClickListener() {
            public void onClick(View sender) {
                onPlay(startPos);
            }
        };

    private OnClickListener rewindListener = new OnClickListener() {
            public void onClick(View sender) {
                if (isPlaying) {
                    int newPos = player.getCurrentPosition() - 5000;
                    if (newPos < playStartMsec)
                        newPos = playStartMsec;
                    player.seekTo(newPos);
                } else {
                    startMarker.requestFocus();
                    markerFocus(startMarker);
                }
            }
        };

    private OnClickListener ffwdListener = new OnClickListener() {
            public void onClick(View sender) {
                if (isPlaying) {
                    int newPos = 5000 + player.getCurrentPosition();
                    if (newPos > playEndMsec)
                        newPos = playEndMsec;
                    player.seekTo(newPos);
                } else {
                    endMarker.requestFocus();
                    markerFocus(endMarker);
                }
            }
        };

    private OnClickListener markStartListener = new OnClickListener() {
            public void onClick(View sender) {
                if (isPlaying) {
                    startPos = waveformView.millisecsToPixels(
                        player.getCurrentPosition());
                    updateDisplay();
                }
            }
        };

    private OnClickListener markEndListener = new OnClickListener() {
            public void onClick(View sender) {
                if (isPlaying) {
                    endPos = waveformView.millisecsToPixels(
                        player.getCurrentPosition());
                    updateDisplay();
                    handlePause();
                }
            }
        };

    private TextWatcher textWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            public void onTextChanged(CharSequence s,
                                      int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                if (startText.hasFocus()) {
                    try {
                        startPos = waveformView.secondsToPixels(
                            Double.parseDouble(
                                startText.getText().toString()));
                        updateDisplay();
                    } catch (NumberFormatException e) {
                    }
                }
                if (endText.hasFocus()) {
                    try {
                        endPos = waveformView.secondsToPixels(
                            Double.parseDouble(
                                endText.getText().toString()));
                        updateDisplay();
                    } catch (NumberFormatException e) {
                    }
                }
            }
        };

    private long getCurrentTime() {
        return System.nanoTime() / 1000000;
    }

    private String getStackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
