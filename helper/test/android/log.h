#ifndef HAPANELD_TEST_ANDROID_LOG_H
#define HAPANELD_TEST_ANDROID_LOG_H

/* The recovery fixture executes the Android logging branch on a host. */
#define ANDROID_LOG_WARN 5
int __android_log_write(int priority, const char *tag, const char *text);

#endif
