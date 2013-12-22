/*
 * Copyright (C) 2009 The Android Open Source Project
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
 *
 */

#include <assert.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <jni.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sched.h>
#include <math.h>
#include <android/log.h>
#include <semaphore.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "threads", __VA_ARGS__)

#define SEMAPHORE

int timespec_diff(const struct timespec *t1, const struct timespec *t2) {
	return t1->tv_nsec - t2->tv_nsec + 1000000000 * (t1->tv_sec - t2->tv_sec);
}

void timespec_bump(struct timespec *tp, int ns) {
	tp->tv_nsec += ns;
	tp->tv_sec += tp->tv_nsec / 1000000000;
	tp->tv_nsec %= 1000000000;
}

double ts_to_double(const struct timespec *tp) {
	return tp->tv_sec + 1e-9 * tp->tv_nsec;
}

void double_to_ts(double t, struct timespec *tp) {
	double intpart;
	tp->tv_nsec = modf(t, &intpart) * 1e9;
	tp->tv_sec = intpart;
}

#define N 100

struct ctx {
#ifdef SEMAPHORE
	sem_t sem;
#else
	int rd_fd;
#endif
	double wr_time[N];
	double rd_time[N];
};

void *reader_thread(void *data) {
	int s = setpriority(PRIO_PROCESS, gettid(), -16);
	struct ctx *ctx = (struct ctx *)data;

#if 0
	pthread_t thread;
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	pthread_create(&thread, &attr, third_wheel_thread, NULL);
#endif

	int i;
	for (i = 0; i < N; i++) {
#ifdef SEMAPHORE
		sem_wait(&ctx->sem);
#else
		char buf[1];
		read(ctx->rd_fd, buf, 1);
#endif
		struct timespec tp;
		clock_gettime(CLOCK_MONOTONIC, &tp);
		ctx->rd_time[i] = ts_to_double(&tp);
	}
	return NULL;
}

void *test_thread(void *data) {
	char *buf = (char *)data;
	int s = setpriority(PRIO_PROCESS, gettid(), -19);
	if (s != 0) {
		sprintf(buf, "set priority (in thread) failed %d", s);
		return NULL;
	}

	struct ctx ctx;

#ifdef SEMAPHORE
#else
	int pipefd[2];
	pipe(pipefd);
	ctx.rd_fd = pipefd[0];
#endif

	pthread_t thread;
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	pthread_create(&thread, &attr, reader_thread, &ctx);

	struct timespec tp;
	clock_gettime(CLOCK_MONOTONIC, &tp);
	int i;
	int max, maxi;
	int sum = 0;
	struct timespec tp2 = tp;
	for (i = 0; i < N; i++) {
		timespec_bump(&tp2, 10000000);
		struct timespec tp3;
		double t2 = ts_to_double(&tp2);
		clock_gettime(CLOCK_MONOTONIC, &tp3);
		double t3 = ts_to_double(&tp3);
		while (t2 > t3) {
			double t = t2;
			double_to_ts(t, &tp2);
			//__android_log_print(ANDROID_LOG_VERBOSE, "Thread",
			//	"tp2 = %d:%d, t2=%g, t3=%g", tp2.tv_sec, tp2.tv_nsec, t2, t3);
			clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &tp2, 0);
			clock_gettime(CLOCK_MONOTONIC, &tp3);
			t3 = ts_to_double(&tp3);
		}
		//clock_gettime(CLOCK_MONOTONIC, &tp3);
		ctx.wr_time[i] = t3;
#ifdef SEMAPHORE
		sem_post(&ctx.sem);
#else
		write(pipefd[1], buf, 1);
#endif
		int delta = timespec_diff(&tp3, &tp2);
		//__android_log_print(ANDROID_LOG_VERBOSE, "Thread", "time %.6f delta %d",
		//	t3, delta);
		sum += delta;
		if (i == 0 || delta > max) {
			max = delta;
			maxi = i;
		}
	}
	void *res;
	pthread_join(thread, &res);

	double maxdelay;
	for (i = 0; i < N; i++) {
		double delay = ctx.rd_time[i] - ctx.wr_time[i];
		if (i == 0 || delay > maxdelay) {
			maxdelay = delay;
		}
		//__android_log_print(ANDROID_LOG_VERBOSE, "Thread", "delta %.6f",
		//	delay);		
	}
	sprintf(buf, "max = %d (%d), mean = %d render = %.6f", max, maxi, sum / N, maxdelay);
	return NULL;
}

#define N_BUFFERS 4

int global_bufsize;
int global_sample_rate;
#define MAX_BUFFER_SIZE 4096

// This is the maximum length of any test
#define TEST_LENGTH 10000

struct renderctx {
	sem_t wake_sem;
	sem_t ready_sem;
	sem_t done_sem;
	int length;
	int16_t buffer[MAX_BUFFER_SIZE * N_BUFFERS];
	int render_ix;
	int play_ix;
	double callback_ts[TEST_LENGTH];
	double cbdone_ts[TEST_LENGTH];
	double thread_ts[TEST_LENGTH];
	double render_ts[TEST_LENGTH];
	int delay100us_cb;
	int delay100us_render;
	jboolean pulse;
	double mark_jitter;
};

struct renderctx global_renderctx;

#define BUF_DELAY 0

void init_renderctx(struct renderctx *ctx) {
	sem_init(&ctx->wake_sem, 0, BUF_DELAY);
	sem_init(&ctx->ready_sem, 0, 0);
	sem_init(&ctx->done_sem, 0, 0);
	ctx->render_ix = 0;
	ctx->play_ix = 0;
}

// engine interfaces
static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine;

// output mix interfaces
static SLObjectItf outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bq_player_play;
static SLAndroidSimpleBufferQueueItf bq_player_buffer_queue;
static SLBufferQueueItf buffer_queue_itf;

double last_ts = 0;

int state = 0;
int count;
pthread_t global_render_thread;

int spin(int n) {
	uint32_t x = 1;
	int i;
	for (i = 0; i < n; i++) {
	    x += 42;
	    x += (x << 10);
	    x ^= (x >> 6);
	}
	return x;
}

int spin100us(int n) {
	int i;
	for (i = 0; i < n; i++) {
		spin(30083);
	}
}

int spinms(int n) {
	return spin100us(n * 10);
}

void *render_thread(void *data) {
	struct renderctx *ctx = (struct renderctx *)data;
	struct sched_param param;

	param.sched_priority = 1;
	int result = pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
	if (result != 0) {
		LOGI("error %d setting thread priority", result);
	}

	int i;
	for (i = 1; i < ctx->length; i++) {
		sem_wait(&ctx->wake_sem);
		struct timespec tp;
		clock_gettime(CLOCK_MONOTONIC, &tp);
		ctx->thread_ts[i] = ts_to_double(&tp);
		int delay = ctx->delay100us_render;
		if (ctx->pulse) delay *= ((i / 50) & 1);
  		spin100us(delay);
		clock_gettime(CLOCK_MONOTONIC, &tp);
		ctx->render_ts[i] = ts_to_double(&tp);
		sem_post(&ctx->ready_sem);
	}
	LOGI("render thread complete");
	return NULL;
}

double minmark, maxmark;

void BqPlayerCallback(SLAndroidSimpleBufferQueueItf queueItf,
  void *data) {
	struct renderctx *ctx = (struct renderctx *)data;
	if (count == 1) {
		pthread_attr_t attr;
		pthread_attr_init(&attr);
		pthread_create(&global_render_thread, &attr, render_thread, &global_renderctx);
		pthread_setname_np(global_render_thread, "RenderThread");
	}
#if 1
  	if (count < ctx->length) {
  		int delay = ctx->delay100us_cb;
		if (ctx->pulse) delay *= ((count / 50) & 1);
  		//delay = ctx->delay100us;
  		struct timespec tp;
		clock_gettime(CLOCK_MONOTONIC, &tp);
		double start = ts_to_double(&tp);
		ctx->callback_ts[count] = start;

		spin100us(delay);
		clock_gettime(CLOCK_MONOTONIC, &tp);
		ctx->cbdone_ts[count] = ts_to_double(&tp);

		double mark = start * global_sample_rate / global_bufsize - count;
		// maybe should count startup separately
		if (count == 100 || mark < minmark) minmark = mark;
		if (count == 100 || mark > maxmark) maxmark = mark;
	  	//LOGI("callback %4i: %.6f (+%.6f) %.6f", count, mark, start - last_ts, ts_to_double(&tp) - start);
	  	if (count == ctx->length - 1) {
	  		double jitter = maxmark - minmark;
	  		double jitterms = jitter * global_bufsize / global_sample_rate * 1000;
	  		ctx->mark_jitter = jitterms;
	  		LOGI("mark jitter = %.6f (%.3fms)", jitter, jitterms);
	  	}
	  	last_ts = start;
	}
#endif
	if (count >= 1) {
		int ok = sem_trywait(&ctx->ready_sem);
		if (ok != 0) {
			LOGI("underrun %d!", count);
		}
	}
	int16_t *buf_ptr = ctx->buffer + global_bufsize * ctx->play_ix;
	memset(buf_ptr, 0, global_bufsize * sizeof(int16_t));
	buf_ptr[0] = 1000;
	SLresult result = (*queueItf)->Enqueue(bq_player_buffer_queue,
		buf_ptr, global_bufsize * 2);
	assert(SL_RESULT_SUCCESS == result);
	ctx->play_ix = (ctx->play_ix + 1) % N_BUFFERS;
	if (count >= 1 && count < ctx->length - BUF_DELAY) sem_post(&ctx->wake_sem);
	count++;
	if (count == ctx->length) {
		sem_post(&ctx->done_sem);
	}
}

void CreateEngine() {
    SLresult result;
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);

    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);

    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE,
      	&engineEngine);
    assert(SL_RESULT_SUCCESS == result);

    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject,
      	0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    LOGI("engine started");
}

void SetupPlayer() {
  	SLDataLocator_AndroidSimpleBufferQueue loc_bufq =
    	{SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, N_BUFFERS};
  	SLDataFormat_PCM format_pcm = {
    	SL_DATAFORMAT_PCM, 1, global_sample_rate * 1000,
    	SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
    	SL_SPEAKER_FRONT_CENTER, SL_BYTEORDER_LITTLEENDIAN
      // TODO: compute real endianness
  	};
  	SLDataSource audio_src = {&loc_bufq, &format_pcm};
  	SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX,
    	outputMixObject};
  	SLDataSink audio_sink = {&loc_outmix, NULL};
  	const SLInterfaceID ids[2] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME};
  	const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
  	SLresult result;
  	result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject,
      	&audio_src, &audio_sink, 2, ids, req);
  	assert(SL_RESULT_SUCCESS == result);
  	result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
  	assert(SL_RESULT_SUCCESS == result);
  	result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY,
      	&bq_player_play);
  	assert(SL_RESULT_SUCCESS == result);
  	result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
      	&bq_player_buffer_queue);
  	assert(SL_RESULT_SUCCESS == result);

}

void ShutdownEngine() {
    SLresult result;
	LOGI("shutting down engine");
  	if (bqPlayerObject != NULL) {
		result = (*bq_player_play)->SetPlayState(bq_player_play,
			SL_PLAYSTATE_PAUSED);
		assert(SL_RESULT_SUCCESS == result);
    	(*bqPlayerObject)->Destroy(bqPlayerObject);
    	bqPlayerObject = NULL;
    	bq_player_play = NULL;
    	bq_player_buffer_queue = NULL;
  	}
  	if (outputMixObject != NULL) {
    	(*outputMixObject)->Destroy(outputMixObject);
    	outputMixObject = NULL;
  	}
  	if (engineObject != NULL) {
    	(*engineObject)->Destroy(engineObject);
    	engineObject = NULL;
    	engineEngine = NULL;
  	}
}

void Java_com_levien_audiobuffersize_AudioBufferSize_initAudio(JNIEnv *env, jobject thiz, jint sample_rate, jint buf_size)
{
	global_sample_rate = sample_rate;
	global_bufsize = buf_size;
}

jstring
Java_com_levien_audiobuffersize_AudioBufferSize_sljitter(JNIEnv *env,
    jobject thiz, jdoubleArray arr, jint length, jint delay100us_cb, jint delay100us_render, jboolean pulse) {

  	CreateEngine();
  	SetupPlayer();

	init_renderctx(&global_renderctx);
	global_renderctx.length = length;
	global_renderctx.delay100us_cb = delay100us_cb;
	global_renderctx.delay100us_render = delay100us_render;
	global_renderctx.pulse = pulse;
	SLresult result;

	result = (*bq_player_buffer_queue)->RegisterCallback(bq_player_buffer_queue,
        &BqPlayerCallback, &global_renderctx);
	assert(SL_RESULT_SUCCESS == result);

	count = 0;
	BqPlayerCallback(bq_player_buffer_queue, &global_renderctx);
	result = (*bq_player_play)->SetPlayState(bq_player_play,
		SL_PLAYSTATE_PLAYING);
	assert(SL_RESULT_SUCCESS == result);
    sem_wait(&global_renderctx.done_sem);
    void *res;
    pthread_join(global_render_thread, &res);
    ShutdownEngine();
	char buf[256];
	(*env)->SetDoubleArrayRegion(env, arr, 0, length, global_renderctx.callback_ts);
	(*env)->SetDoubleArrayRegion(env, arr, length, length, global_renderctx.cbdone_ts);
	(*env)->SetDoubleArrayRegion(env, arr, 2 * length, length, global_renderctx.thread_ts);
	(*env)->SetDoubleArrayRegion(env, arr, 3 * length, length, global_renderctx.render_ts);
	sprintf(buf, "OpenSL callback jitter = %.3fms", global_renderctx.mark_jitter);
    return (*env)->NewStringUTF(env, buf);
}

/* Adapted from hello jni example */
jstring
Java_com_levien_audiobuffersize_AudioBufferSize_test( JNIEnv* env,
                                                  jobject thiz )
{
	char buf[256];
	int s;
	pthread_t thread;
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	struct sched_param param;
	param.sched_priority = 0;
	s = pthread_attr_setschedparam(&attr, &param);
	if (s != 0) {
		sprintf(buf, "setschedparam failed %d", s);
	}
#if 0
	if (s == 0) {
		s = pthread_attr_setschedpolicy(&attr, SCHED_RR);
		if (s != 0) {
			sprintf(buf, "setschedpolicy failed %d", s);
		}
	}
#endif
	if (s == 0) {
		s = pthread_create(&thread, &attr, test_thread, buf);
		//s = setpriority(PRIO_PROCESS, thread, 0);
		if (s != 0) {
			sprintf(buf, "set priority failed %d", s);
		}
	}
	if (s == 0) {
		void *res;
		pthread_join(thread, &res);
	}
    pthread_attr_destroy(&attr);
    return (*env)->NewStringUTF(env, buf);
}

jstring
Java_com_levien_audiobuffersize_AudioBufferSize_cpuBound(JNIEnv *env, jobject thiz)
{
	char buf[256];
	spinms(200);
	struct timespec tp;
	clock_gettime(CLOCK_MONOTONIC, &tp);
	double start = ts_to_double(&tp);
	int n2 = 1000;
	spinms(n2);
	clock_gettime(CLOCK_MONOTONIC, &tp);
	double delta = ts_to_double(&tp) - start;
	sprintf(buf, "%d iters in %.6fs", n2, delta);
    return (*env)->NewStringUTF(env, buf);
}
