/*
 * Copyright (C) 2014 Google Inc.
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

package com.android.grandcanyon;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.IllegalArgumentException;

/**
 * This class provides a basic demonstration of how to use the AudioRecord
 * class. Every time a buffer is recorded, it gets played back on an AudioTrack.
 */
public class GrandCanyon extends Activity {
    private boolean mIsRecording = false;
    private static final Object sRecordingLock = new Object();

    // -------------------------------------------------
    // UI stuff
    static final private int BACK_ID = Menu.FIRST;

    static final private int RECORDER_INITIALIZATION_PB = 1;

    private TextView mConsoleView; // where we display the log of the last action

    // -------------------------------------------------
    // Audio stuff
    private AudioManager mAudioManager;

    private byte[] mAudioByteArray;
    private int mMinRecordBuffSizeInBytes = 0;
    private int mMinPlayBuffSizeInBytes = 0;

    private AudioRecord mRecorder;
    private AudioTrack mAudioTrack;

    // our default recording settings
    private static final int mSamplingRate = 16000;
    private static final int mSelectedRecordSource = MediaRecorder.AudioSource.DEFAULT;
    private static final int mChannelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int mChannelConfigOut = AudioFormat.CHANNEL_OUT_MONO;
    private static final int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int mDelayDeciSeconds = 20;

    Thread mWriterThread;
    // thread for pulling data from the native AudioRecord
    private volatile Thread mReaderThread;

    // -------------------------------------------------
    // for record log
    private static final String TAG = "GrandCanyon";

    private static final int pipeLength = 10 * mSamplingRate * 2;
    Pipe mPipe = new Pipe(pipeLength);

    public GrandCanyon() {
        log("GrandCanyon()");
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // -------------------------------------------------
        // UI stuff

        log("onCreate()");

        super.onCreate(savedInstanceState);

        // Inflate our UI from its XML layout description.
        setContentView(R.layout.grandcanyon_activity);

        // Initialize the console view
        mConsoleView = (TextView) findViewById(R.id.labelconsole);
        mConsoleView.setText(getText(R.string.textEmptyConsole));

        // --------------------------------
        // UI button click listeners
        // --------------------------------
        // Hook up button presses to the appropriate event handler.

        // A call-back for when the user presses the Record button
        ((Button) findViewById(R.id.buttonRecord)).setOnClickListener(
            new OnClickListener() {
                public void onClick(View v) {
                    startRecording();
                }
            });

        // A call-back for when the user presses the Stop button
        ((Button) findViewById(R.id.buttonStopRecord)).setOnClickListener(
            new OnClickListener() {
                public void onClick(View v) {
                    stopRecording();
                }
            });

        // A call-back for when the user clicks the "Mic Mute" checkbox.
        CheckBox micMuteCB = (CheckBox) findViewById(R.id.micMuteCB);
        micMuteCB.setOnClickListener(
            new OnClickListener() {
                public void onClick(View v) {
                    mAudioManager.setMicrophoneMute(((CheckBox)v).isChecked());
                    // ((CheckBox) findViewById(R.id.saveRawCB)).setChecked(false);
                    updateUI();
                }
            });
        micMuteCB.setEnabled(true);
        micMuteCB.setChecked(mAudioManager.isMicrophoneMute());

        SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setProgress(mDelayDeciSeconds);
        seekBar.setSecondaryProgress(0);
        seekBar.setOnSeekBarChangeListener(
            new OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (progress >= 0 && progress <= 100) {
                        mDelayDeciSeconds = progress;
                    }
                }
                public void onStartTrackingTouch(SeekBar seekBar) {
                }
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
        });
    }

    // Called when the activity is about to start interacting with the user.
    @Override
    protected void onResume() {
        super.onResume();
        log("onResume()");
        updateUI();
    }

    /** Called when the activity is about to be paused. */
    @Override
    protected void onPause()
    {
        super.onPause();
        stopRecording();
    }

    // Called when your activity's options menu needs to be created.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, BACK_ID, 0, R.string.finish).setShortcut('0', 'b');
        return true;
    }

    // Called when a menu item is selected.
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case BACK_ID:
            stopRecording();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --------------------------------------------------------------------------------------------
    // UI update
    // --------------------------------

    private void updateUI() {
        synchronized (sRecordingLock) {
            if (mIsRecording) {
                findViewById(R.id.buttonRecord).setEnabled(false);
                findViewById(R.id.buttonStopRecord).setEnabled(true);
            } else {
                findViewById(R.id.buttonRecord).setEnabled(true);
                findViewById(R.id.buttonStopRecord).setEnabled(false);
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Convenience methods
    // --------------------------------

    private void startRecording() {
        synchronized (sRecordingLock) {
            mIsRecording = true;
        }
        updateUI();

        boolean successful;
        successful = initPlay();
        if (successful) {
            log("Ready to play.");
        } else {
            log("Play init failed");
        }
        successful = initRecord();
        if (successful) {
            log("Ready to go.");
            startRecordingForReal();
        } else {
            log("Recorder initialization error.");
            //showDialog(RECORDER_INITIALIZATION_PB);
            Toast.makeText(this, "failed", Toast.LENGTH_LONG).show();
            synchronized (sRecordingLock) {
                mIsRecording = false;
            }
            updateUI();
        }
    }

    private boolean initPlay() {
        mMinPlayBuffSizeInBytes = AudioTrack.getMinBufferSize(mSamplingRate, mChannelConfigOut, mAudioFormat);
        log("min buff size = " + mMinPlayBuffSizeInBytes + " bytes");
        if (mMinPlayBuffSizeInBytes <= 0) {
            return false;
        }
        if (mAudioTrack != null) {
            mAudioTrack.release();
            mAudioTrack = null;
        }
        try {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSamplingRate,
                    mChannelConfigOut, mAudioFormat, 2 * mMinPlayBuffSizeInBytes,
                    AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    private boolean initRecord() {
        mMinRecordBuffSizeInBytes = AudioRecord.getMinBufferSize(mSamplingRate,
                mChannelConfig, mAudioFormat);
        if (mMinRecordBuffSizeInBytes <= 0) {
            return false;
        }

        // allocate the byte array to read the audio data
        mAudioByteArray = new byte[mMinRecordBuffSizeInBytes / 2];

        try {
            mRecorder = new AudioRecord(mSelectedRecordSource, mSamplingRate,
                    mChannelConfig, mAudioFormat, 2 * mMinRecordBuffSizeInBytes);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (mRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            mRecorder.release();
            mRecorder = null;
            return false;
        }

        return true;
    }

    private void startRecordingForReal() {

        mPipe.flush();
        byte[] zeroes = new byte[128];
        for (int totalWritten = 0; totalWritten < pipeLength; ) {
            int written = mPipe.write(zeroes, 0, zeroes.length);
            if (written <= 0) {
                break;
            }
            totalWritten += written;
            if (totalWritten >= mDelayDeciSeconds * mSamplingRate * 2 / 10) {
                break;
            }
        }

        Runnable writerRunnable = new WriterRunnable(mPipe, mAudioTrack);
        mWriterThread = new Thread(writerRunnable);
        mWriterThread.setName("GrandCanyonWriter");
        mWriterThread.start();

        Runnable readerRunnable = new ReaderRunnable(mPipe, mRecorder);
        mReaderThread = new Thread(readerRunnable);
        mReaderThread.setName("GrandCanyonReader");
        mReaderThread.start();

        long startTime = SystemClock.uptimeMillis();
        mRecorder.startRecording();
        if (mRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            stopRecording();
            Toast.makeText(this, "startRecording() failed", Toast.LENGTH_LONG).show();
            return;
        }
        log("Start time: " + (long) (SystemClock.uptimeMillis() - startTime) + " ms");
        mConsoleView.setText(R.string.textRecording);
    }

    private void stopRecording() {
        synchronized (sRecordingLock) {
            stopRecordingForReal();
            mIsRecording = false;
        }
        updateUI();
    }

    private void stopRecordingForReal() {

        // stop streaming
        Thread zeThread = mReaderThread;
        mReaderThread = null;
        if (zeThread != null) {
            zeThread.interrupt();
            while (zeThread.isAlive()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        zeThread = mWriterThread;
        mWriterThread = null;
        if (zeThread != null) {
            zeThread.interrupt();
            while (zeThread.isAlive()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        // release resources
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        }

        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }

        displayStatus(getText(R.string.textEmptyConsole));
    }

    // Implementation of Runnable for the audio playback side
    static class WriterRunnable implements Runnable
    {
        private final Pipe mPipe;
        private final AudioTrack mAudioTrack;
        WriterRunnable(Pipe pipe, AudioTrack audioTrack)
        {
            mPipe = pipe;
            mAudioTrack = audioTrack;
        }
        public void run()
        {
            if (mAudioTrack != null) {
                mAudioTrack.play();
            }
            byte[] buffer = new byte[128];
            while (!Thread.interrupted()) {
                int red = mPipe.read(buffer, 0, buffer.length);
                if (red < 0) {
                    break;
                }
                if (red == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                if (mAudioTrack != null) {
                    mAudioTrack.write(buffer, 0, red);
                }
            }
        }
    }

    // Implementation of Runnable for the audio capture side
    class ReaderRunnable implements Runnable
    {
        private final Pipe mPipe;
        private final AudioRecord mAudioRecord;
        ReaderRunnable(Pipe pipe, AudioRecord audioRecord)
        {
            mPipe = pipe;
            mAudioRecord = audioRecord;
        }
        public void run() {
            while (!Thread.interrupted()) {
                // read from native recorder
                int nbBytesRead = mAudioRecord.read(mAudioByteArray, 0,
                        mMinRecordBuffSizeInBytes / 2);
                if (nbBytesRead > 0) {
                    mPipe.write(mAudioByteArray, 0, nbBytesRead);
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Misc
    // --------------------

    private static void log(String msg) {
        Log.v(TAG, "" + msg);
    }

    private void displayStatus(CharSequence msg) {
        mConsoleView.setText(msg);
    }

}
