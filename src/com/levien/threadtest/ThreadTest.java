/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.levien.threadtest;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class ThreadTest extends Activity
{
	public class AudioParams {
		AudioParams(int sr, int bs) {
			sampleRate = sr;
			bufferSize = bs;
		}
		int sampleRate;
		int bufferSize;
	}

	public class TestThread implements Runnable {

		@Override
		public void run() {
			logUI("Thread running!");
			logUI(cpuBound());
			logUI(sljitter());
		}
		
		public void logUI(final String text) {
			runOnUiThread(new Runnable() {
				public void run() {
					log(text);
				}
			});
		}
		
	}
	String msgLog = "";
	
	synchronized void log(String text) {
		msgLog += text + "\n";
        final TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setText(msgLog);
	}
	
    /** Called when the activity is first created. */
	@Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        log(Build.MANUFACTURER + " " + Build.MODEL + " " + Build.ID + " (api " + Build.VERSION.SDK_INT + ")");
        
        //start();
        AudioParams params = new AudioParams(44100, 768);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        	getJbMr1Params(params);
        }
        initAudio(params.sampleRate, params.bufferSize);
        Button button = (Button) findViewById(R.id.button1);
        button.setOnClickListener(new OnClickListener() {
        	public void onClick(View v) {
        		log(test());
        		//tv.setText(tv.getText() + "\n" + test());
        	}
        });
        Thread t = new Thread(new TestThread());
        t.start();
    }

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    void getJbMr1Params(AudioParams params) {
        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
    	String sr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
    	String bs = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
    	log("Sample rate = " + sr + ", buf size = " + bs);
    	params.sampleRate = Integer.parseInt(sr);
    	params.bufferSize = Integer.parseInt(bs);

    }

    public native void initAudio(int sample_rate, int buf_size);
    public native String sljitter();
    
    public native String test();
    public native String cpuBound();

    /* this is used to load the 'hello-jni' library on application
     * startup. The library has already been unpacked into
     * /data/data/com.example.hellojni/lib/libhello-jni.so at
     * installation time by the package manager.
     */
    static {
        System.loadLibrary("hello-jni");
    }
}
