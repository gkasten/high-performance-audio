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

void *third_wheel_thread(void *data) {
	struct timespec tp;
	tp.tv_sec = 10;
	tp.tv_nsec = 0;
	clock_nanosleep(CLOCK_MONOTONIC, 0, &tp, NULL);
	return NULL;
}

void *reader_thread(void *data) {
	int s = setpriority(PRIO_PROCESS, 0, -16);
	struct ctx *ctx = (struct ctx *)data;

	pthread_t thread;
	pthread_attr_t attr;
	pthread_attr_init(&attr);
	pthread_create(&thread, &attr, third_wheel_thread, NULL);


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

/* This is a trivial JNI example where we use a native method
 * to return a new VM String. See the corresponding Java source
 * file located at:
 *
 *   apps/samples/hello-jni/project/src/com/example/hellojni/HelloJni.java
 */
jstring
Java_com_example_hellojni_HelloJni_stringFromJNI( JNIEnv* env,
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

