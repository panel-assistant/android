/* Test builds only: the directory that stands in for /tmp in fixed fixture paths. The unit-test
 * Makefile gives each test binary its own, so tests that share fixture names run concurrently. */
#ifndef HAPANELD_TEST_TMP_H
#define HAPANELD_TEST_TMP_H
#ifndef HAPANELD_TEST_TMP
#define HAPANELD_TEST_TMP "/tmp"
#endif
#endif
