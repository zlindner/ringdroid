/*
 * Copyright (C) 2015 Google Inc.
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

import java.nio.ShortBuffer;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.ringdroid.soundfile.SoundFile;

class SamplePlayer {
    public interface OnCompletionListener {
        public void onCompletion();
    };

    private ShortBuffer shortBuffSamples;
    private int originalSampleRate;
    private int numChannels;
    private int numberOfSamples;  // Number of samples per channel.
    private AudioTrack originalAudioTrack;
    private short[] buffer;
    private int playbackStart;  // Start offset, in samples.
    private Thread playThread;
    private boolean keepPlaying;
    private OnCompletionListener completionListener;

    public SamplePlayer(ShortBuffer samples, int sampleRate, int channels, int numSamples) {
        shortBuffSamples = samples;
        originalSampleRate = sampleRate;
        numChannels = channels;
        numberOfSamples = numSamples;
        playbackStart = 0;

        int bufferSize = AudioTrack.getMinBufferSize(
                originalSampleRate,
                numChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        // make sure minBufferSize can contain at least 1 second of audio (16 bits sample).
        if (bufferSize < numChannels * originalSampleRate * 2) {
            bufferSize = numChannels * originalSampleRate * 2;
        }
        buffer = new short[bufferSize/2]; // bufferSize is in Bytes.
        originalAudioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                originalSampleRate,
                numChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.length * 2,
                AudioTrack.MODE_STREAM);
        // Check when player played all the given data and notify user if completionListener is set.
        originalAudioTrack.setNotificationMarkerPosition(numberOfSamples - 1);  // Set the marker to the end.
        originalAudioTrack.setPlaybackPositionUpdateListener(
                new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onPeriodicNotification(AudioTrack track) {}

            @Override
            public void onMarkerReached(AudioTrack track) {
                stop();
                if (completionListener != null) {
                    completionListener.onCompletion();
                }
            }
        });
        playThread = null;
        keepPlaying = true;
        completionListener = null;
    }

    public SamplePlayer(SoundFile sf) {
        this(sf.getSamples(), sf.getSampleRate(), sf.getChannels(), sf.getNumSamples());
    }

    public void setOnCompletionListener(OnCompletionListener listener) {
        completionListener = listener;
    }

    public boolean isPlaying() {
        return originalAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    public boolean isPaused() {
        return originalAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PAUSED;
    }

    public void start() {
        if (isPlaying()) {
            return;
        }
        keepPlaying = true;
        originalAudioTrack.flush();
        originalAudioTrack.play();
        // Setting thread feeding the audio samples to the audio hardware.
        // (Assumes numChannels = 1 or 2).
        playThread = new Thread () {
            public void run() {
                int position = playbackStart * numChannels;
                shortBuffSamples.position(position);
                int limit = numberOfSamples * numChannels;
                while (shortBuffSamples.position() < limit && keepPlaying) {
                    int numSamplesLeft = limit - shortBuffSamples.position();
                    if(numSamplesLeft >= buffer.length) {
                        shortBuffSamples.get(buffer);
                    } else {
                        for(int i=numSamplesLeft; i<buffer.length; i++) {
                            buffer[i] = 0;
                        }
                        shortBuffSamples.get(buffer, 0, numSamplesLeft);
                    }
                    // TODO(nfaralli): use the write method that takes a ByteBuffer as argument.
                    originalAudioTrack.write(buffer, 0, buffer.length);
                }
            }
        };
        playThread.start();
    }

    public void pause() {
        if (isPlaying()) {
            originalAudioTrack.pause();
            // originalAudioTrack.write() should block if it cannot write.
        }
    }

    public void stop() {
        if (isPlaying() || isPaused()) {
            keepPlaying = false;
            originalAudioTrack.pause();  // pause() stops the playback immediately.
            originalAudioTrack.stop();   // Unblock originalAudioTrack.write() to avoid deadlocks.
            if (playThread != null) {
                try {
                    playThread.join();
                } catch (InterruptedException e) {
                    Log.e("SamplePlayer", "stop() error joining playThread\n" + e.toString());
                }
                playThread = null;
            }
            originalAudioTrack.flush();  // just in case...
        }
    }

    public void release() {
        stop();
        originalAudioTrack.release();
    }

    public void seekTo(int msec) {
        boolean wasPlaying = isPlaying();
        stop();
        playbackStart = (int)(msec * (originalSampleRate / 1000.0));
        if (playbackStart > numberOfSamples) {
            playbackStart = numberOfSamples;  // Nothing to play...
        }
        originalAudioTrack.setNotificationMarkerPosition(numberOfSamples - 1 - playbackStart);
        if (wasPlaying) {
            start();
        }
    }

    public int getCurrentPosition() {
        return (int)((playbackStart + originalAudioTrack.getPlaybackHeadPosition()) *
                (1000.0 / originalSampleRate));
    }
}
