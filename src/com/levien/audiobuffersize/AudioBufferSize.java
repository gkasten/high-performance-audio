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
package com.levien.audiobuffersize;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.levien.threadtest.R;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

public class AudioBufferSize extends Activity
{
	int count = 0;
	final static int SR_TEST_LENGTH = 10000;
	final static int TEST_LENGTH = 2000;
	class AudioParams {
		AudioParams(int sr, int bs) {
			confident = false;
			sampleRate = sr;
			bufferSize = bs;
		}
		public String toString() {
			return "sampleRate=" + sampleRate + " bufferSize=" + bufferSize;
		}
		boolean confident;
		int sampleRate;
		int bufferSize;
	}
	AudioParams params;
	
	class JitterMeasurement {
		double rate;
		double jitter;
	}

	class ThreadMeasurement {
		double cbDone;
		double renderStart;
		double renderEnd;
		public String toString() {
			NumberFormat f = NumberFormat.getInstance();
			f.setMinimumFractionDigits(3);
			f.setMaximumFractionDigits(3);
			return "cbDone=" + f.format(cbDone * 1000) +
					", renderStart=" + f.format(renderStart * 1000) +
					", renderEnd=" + f.format(renderEnd * 1000);
		}
	}
	
	public class TestThread implements Runnable {

		@Override
		public void run() {
			logUI(cpuBound());
			logUI("audio tests based on " + TEST_LENGTH + " samples");
			initAudio(params.sampleRate, 64);
			double ts[] = new double[SR_TEST_LENGTH * 4];
			sljitter(ts, SR_TEST_LENGTH, 0, 0, false);
			AudioParams newParams = analyzeBufferSize(params, ts);
			logUI("detected params: " + newParams);
			updateAudioParams(params, newParams);
			ts = new double[TEST_LENGTH * 4];
	        initAudio(params.sampleRate, params.bufferSize);
	        double rate = 0.0;
	        for (int exp = 0; exp < 4; exp++) {
	        	boolean pulsed = (exp & 1) != 0;
	        	boolean runInThread = (exp & 2) != 0;
	        	logUI("experiment: " + (pulsed ? "pulsed" : "not pulsed") +
	        			(runInThread ? " in thread" : ""));
	        	int badCount = 0;
		        for (int i = 0; i < 100; i += 1) {
		    		NumberFormat f = NumberFormat.getInstance();
		    		f.setMinimumFractionDigits(1);
		    		f.setMaximumFractionDigits(1);
					logUI(f.format(i*.1) + ": " + sljitter(ts, TEST_LENGTH,
							runInThread ? 0 : i,
							runInThread ? i : 0, pulsed));
					JitterMeasurement jm = analyzeDrift(ts, rate);
					if (rate == 0) rate = jm.rate;
					if (i == 0 && exp == 0) {
						reportDrift(newParams, jm);
					}
					ThreadMeasurement tm = analyzeJitter(ts);
					logUI(tm.toString());
					if (jm.jitter > 0.02 || tm.renderEnd > 0.06) badCount++;
					else badCount = 0;
					if (badCount >= 2) break;
				}
		        logUI("");
	        }
	        postResults();
		}
		
		void updateAudioParams(AudioParams dst, AudioParams src) {
			if (!dst.confident) {
				dst.sampleRate = src.sampleRate;
				dst.bufferSize = src.bufferSize;
			}
		}
		
		JitterMeasurement analyzeDrift(double[] arr, double forceRate) {
			final int startupSkip = 100;
			int n = arr.length / 4;
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
			double jitRate = forceRate == 0 ? beta : forceRate;
			//double alpha = (ys - beta * xs) / count;
			double minJit = 0;
			double maxJit = 0;
			for (int i = startupSkip; i < n; i++) {
				double err = jitRate * i - arr[i];
				if (i == startupSkip || err < minJit) minJit = err;
				if (i == startupSkip || err > maxJit) maxJit = err;
			}
			JitterMeasurement jm = new JitterMeasurement();
			jm.rate = beta;
			jm.jitter = maxJit - minJit;
			NumberFormat f = NumberFormat.getInstance();
			f.setMinimumFractionDigits(3);
			f.setMaximumFractionDigits(3);
			logUI("ms per tick = " + f.format(jm.rate * 1000) +
					"; jitter (lr) = " + f.format(jm.jitter * 1000));
			return jm;
		}

		void reportDrift(AudioParams params, JitterMeasurement jm) {
			double actualSr = params.bufferSize / jm.rate;
			logUI("Actual sample rate = " + actualSr);
		}

	}
	
	class MeasureBufferSize implements Runnable {
		@Override
		public void run() {
			int[] sampleRates = {44100, 48000};
	        logUI(Build.MANUFACTURER + " " + Build.MODEL + " " + Build.VERSION.RELEASE + " " + Build.ID + " (api " + Build.VERSION.SDK_INT + ")");
	        
	        params = new AudioParams(44100, 768);
	        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
	        	getJbMr1Params(params);
	        }
	        double bestJitter = 0;
	        int bestSr = 0;
	        int bestBs = 0;
			for (int i = 0; i < sampleRates.length; i++) {
				params.sampleRate = sampleRates[i];
				initAudio(params.sampleRate, 64);
				double ts[] = new double[SR_TEST_LENGTH * 4];
				sljitter(ts, SR_TEST_LENGTH, 0, 0, false);
				AudioParams newParams = analyzeBufferSize(params, ts);
				logUI(newParams.toString());
				initAudio(newParams.sampleRate, newParams.bufferSize);
				ts = new double[TEST_LENGTH * 4];
				sljitter(ts, TEST_LENGTH, 0, 0, false);
				JitterMeasurement jm = analyzeDrift(ts, 0);
				if (i == 0 || jm.jitter < bestJitter) {
					bestJitter = jm.jitter;
					bestSr = newParams.sampleRate;
					bestBs = newParams.bufferSize;
				}
			}
			final String bestBsStr = Integer.toString(bestBs);
			final String bestSrStr = Integer.toString(bestSr / 1000) +
					(bestSr % 1000 == 0 ? "" : "." + Integer.toString(bestSr % 1000 / 100)) +
					"kHz";
			logUI("result: " + bestBs + " " + bestSr);
			runOnUiThread(new Runnable () {
				public void run() {
					Button button = (Button)findViewById(R.id.startbutton);
					button.setText(R.string.done);
					button = (Button)findViewById(R.id.upload);
					button.setEnabled(true);
					TextView bstv = (TextView)findViewById(R.id.buffersize);
					bstv.setText(bestBsStr);
					TextView srtv = (TextView)findViewById(R.id.samplerate);
					srtv.setText(bestSrStr);
				}
			});
		}		
	}
	String msgLog = "";
	
	synchronized void log(String text) {
		msgLog += text + "\n";
        final TextView tv = (TextView) findViewById(R.id.textView1);
        //tv.setText(msgLog);
        tv.append(text + "\n");
        final ScrollView sv = (ScrollView) findViewById(R.id.scrollView);
        sv.fullScroll(View.FOCUS_DOWN);
	}
	
    /** Called when the activity is first created. */
	@Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);
        final Button button = (Button) findViewById(R.id.startbutton);
        button.setOnClickListener(new OnClickListener() {
        	public void onClick(View v) {
        		button.setEnabled(false);
        		button.setText(R.string.running);
        		new Thread(new MeasureBufferSize()).start();
        	}
        });
        final Button uploadButton = (Button) findViewById(R.id.upload);
        uploadButton.setOnClickListener(new OnClickListener() {
        	public void onClick(View v) {
        		uploadButton.setEnabled(false);
        		new Thread(new Runnable() {
        			public void run() {
        				postResults();
        			}
        		}).start();
        	}
        });
    }

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    void getJbMr1Params(AudioParams params) {
        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
    	String sr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
    	String bs = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
    	params.confident = true;
    	params.sampleRate = Integer.parseInt(sr);
    	params.bufferSize = Integer.parseInt(bs);
    	logUI("from platform: " + params);
    }
	
	ThreadMeasurement analyzeJitter(double[] arr) {
		int n = arr.length / 4;
		double maxCbDoneDelay = 0;
		double maxThreadDelay = 0;
		double maxRenderDelay = 0;
		for (int i = 100; i < n; i++) {
			double callback_ts = arr[i - 0];
			double cbdone_ts = arr[n + i];
			double thread_ts = arr[2 * n + i];
			double render_ts = arr[3 * n + i];
			maxCbDoneDelay = Math.max(maxCbDoneDelay, cbdone_ts - callback_ts);
			maxThreadDelay = Math.max(maxThreadDelay, thread_ts - callback_ts);
			maxRenderDelay = Math.max(maxRenderDelay, render_ts - callback_ts);
		}
		NumberFormat f = NumberFormat.getInstance();
		f.setMinimumFractionDigits(3);
		f.setMaximumFractionDigits(3);
		ThreadMeasurement tm = new ThreadMeasurement();
		tm.cbDone = maxCbDoneDelay;
		tm.renderStart = maxThreadDelay;
		tm.renderEnd = maxRenderDelay;
		return tm;
	}

	AudioParams analyzeBufferSize(AudioParams params, double[] arr) {
		final int startupSkip = 100;
		int n = arr.length / 4;
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
	
	JitterMeasurement analyzeDrift(double[] arr, double forceRate) {
		final int startupSkip = 100;
		int n = arr.length / 4;
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
		double jitRate = forceRate == 0 ? beta : forceRate;
		//double alpha = (ys - beta * xs) / count;
		double minJit = 0;
		double maxJit = 0;
		for (int i = startupSkip; i < n; i++) {
			double err = jitRate * i - arr[i];
			if (i == startupSkip || err < minJit) minJit = err;
			if (i == startupSkip || err > maxJit) maxJit = err;
		}
		JitterMeasurement jm = new JitterMeasurement();
		jm.rate = beta;
		jm.jitter = maxJit - minJit;
		NumberFormat f = NumberFormat.getInstance();
		f.setMinimumFractionDigits(3);
		f.setMaximumFractionDigits(3);
		logUI("ms per tick = " + f.format(jm.rate * 1000) +
				"; jitter (lr) = " + f.format(jm.jitter * 1000));
		return jm;
	}

	void postResults() {
		HttpClient httpClient = new DefaultHttpClient();
		HttpPost httpPost = new HttpPost("http://audiobuffersize.appspot.com/sign");
		List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
		nameValuePairs.add(new BasicNameValuePair("content", msgLog));
		try {
			httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
		} catch (UnsupportedEncodingException e) {
			// it's just ascii, can't happen
		}
		try {
			HttpResponse response = httpClient.execute(httpPost);
			logUI(EntityUtils.toString(response.getEntity()));
		} catch (ClientProtocolException e) {
		} catch (IOException e) {
		}
		runOnUiThread(new Runnable() {
			public void run() {
		        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		        Button uploadButton = (Button)findViewById(R.id.upload);
		        uploadButton.setText(R.string.done);
			}
		});
	}

	public void logUI(final String text) {
		runOnUiThread(new Runnable() {
			public void run() {
				log(text);
			}
		});
	}
	
	public native void initAudio(int sample_rate, int buf_size);
    public native String sljitter(double ts[], int length, int delay100us_cb, int delay100us_render, boolean pulse);
    
    public native String test();
    public native String cpuBound();

    static {
        System.loadLibrary("audiobufferjni");
    }
}
