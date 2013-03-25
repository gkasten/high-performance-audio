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

import java.text.NumberFormat;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class ThreadTest extends Activity
{
	int count = 0;
	final static int TEST_LENGTH = 10000;
	public class AudioParams {
		AudioParams(int sr, int bs) {
			sampleRate = sr;
			bufferSize = bs;
		}
		int sampleRate;
		int bufferSize;
	}
	AudioParams params;
	
	public class JitterMeasurement {
		double rate;
		double jitter;
	}

	public class TestThread implements Runnable {

		@Override
		public void run() {
			logUI(cpuBound());
			initAudio(params.sampleRate, 64);
			double ts[] = new double[TEST_LENGTH * 3];
			sljitter(ts, 0, 0, false);
			AudioParams newParams = analyzeBufferSize(params, ts);
	        initAudio(params.sampleRate, params.bufferSize);
			for (int i = 0; i < 20; i += 2) {
				logUI(i*.1 + ": " + sljitter(ts, i, 0, false));
				JitterMeasurement jm = analyzeDrift(ts);
				if (i == 0) {
					reportDrift(newParams, jm);
				}
				logUI(analyzeJitter(ts));
			}
		}
		
		AudioParams analyzeBufferSize(AudioParams params, double[] arr) {
			final int startupSkip = 100;
			int n = arr.length / 3;
			int count = 0;
			for (int i = startupSkip; i < n; i++) {
				double delta = arr[i] - arr[i - 1];
				if (delta > 0.001) count++;
			}
			double bufferSizeEst = 64.0 * (n - startupSkip) / count;
			logUI("buffer size estimate = " + bufferSizeEst);
			// We know the buffer size is always a multiple of 16
			int bufferSize = 16 * (int) Math.round(bufferSizeEst / 16);
			AudioParams newParams = new AudioParams(params.sampleRate, bufferSize);
			return newParams;
		}
		
		JitterMeasurement analyzeDrift(double[] arr) {
			final int startupSkip = 100;
			int n = arr.length / 3;
			// Do linear regression to find timing drift
			double xys = 0, xs = 0, ys = 0, x2s = 0;
			int count = n - startupSkip;
			for (int i = startupSkip; i < n; i++) {
				double x = i;
				double y = arr[i];
				xys += x * y;
				xs += x;
				ys += y;
				x2s += x * x;
			}
			double beta = (count * xys - xs * ys) / (count * x2s - xs * xs);
			double alpha = (ys - beta * xs) / count;
			double minJit = 0;
			double maxJit = 0;
			for (int i = startupSkip; i < n; i++) {
				double err = alpha + beta * i - arr[i];
				if (i == startupSkip || err < minJit) minJit = err;
				if (i == startupSkip || err > maxJit) maxJit = err;
			}
			JitterMeasurement jm = new JitterMeasurement();
			jm.rate = beta;
			jm.jitter = maxJit - minJit;
			logUI("ms per tick = " + jm.rate * 1000 + "; jitter (lr) = " + jm.jitter * 1000);
			return jm;
		}

		void reportDrift(AudioParams params, JitterMeasurement jm) {
			double actualSr = params.sampleRate / jm.rate;
			logUI("Actual sample rate = " + actualSr);
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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);
        log(Build.MANUFACTURER + " " + Build.MODEL + " " + Build.ID + " (api " + Build.VERSION.SDK_INT + ")");
        
        //start();
        params = new AudioParams(44100, 768);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        	getJbMr1Params(params);
        }
        Button button = (Button) findViewById(R.id.button1);
        button.setOnClickListener(new OnClickListener() {
        	public void onClick(View v) {
        		double ts[] = new double[TEST_LENGTH * 3];
        		
        		log(count + ": " + sljitter(ts, count, 0, false));
        		count++;
        		for (int i = 0; i < 10; i++) {
        			log("" + i +": " + ts[i]);
        		}
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
	
	String analyzeJitter(double[] arr) {
		int n = arr.length / 3;
		double maxThreadDelay = 0;
		double maxRenderDelay = 0;
		for (int i = 100; i < n; i++) {
			double callback_ts = arr[i - 0];
			double thread_ts = arr[n + i];
			double render_ts = arr[2 * n + i];
			maxThreadDelay = Math.max(maxThreadDelay, thread_ts - callback_ts);
			maxRenderDelay = Math.max(maxRenderDelay, render_ts - callback_ts);
		}
		NumberFormat f = NumberFormat.getInstance();
		f.setMinimumFractionDigits(3);
		f.setMaximumFractionDigits(3);
		return "maxThread=" + f.format(maxThreadDelay * 1000) + "; maxRender=" + f.format(maxRenderDelay * 1000);
	}

	public native void initAudio(int sample_rate, int buf_size);
    public native String sljitter(double ts[], int delay100us_cb, int delay100us_render, boolean pulse);
    
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
