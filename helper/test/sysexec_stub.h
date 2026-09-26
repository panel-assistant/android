#ifndef HAPANELD_SYSEXEC_STUB_H
#define HAPANELD_SYSEXEC_STUB_H

#include <stddef.h>

// Deterministic failure/output controls for host tests. Failure rules match either a shell command
// or an argv display, while the counters keep those execution paths distinct.
void sysexec_stub_reset(void);
void sysexec_stub_fail_run(const char *needle, int status);
void sysexec_stub_timeout_run(const char *needle);
int  sysexec_stub_count_run(const char *needle);
int  sysexec_stub_last_run(char *out, size_t size);
int  sysexec_stub_count_argv(const char *path, const char *const argv[], int quiet);
int  sysexec_stub_count_argv_calls(void);
void sysexec_stub_block_run(const char *needle);
void sysexec_stub_wait_blocked(void);
void sysexec_stub_release_run(void);
void sysexec_stub_add_popen(const char *needle, const char *output, int close_status);
void sysexec_stub_clear_popen_rules(void);
long sysexec_stub_last_pclose_offset(void);
void sysexec_stub_set_spawn_result(int status);
void sysexec_stub_set_spawn_real(int enabled);
int  sysexec_stub_count_sleep(void);
unsigned long sysexec_stub_total_sleep_ms(void);
// Deadline-form controls: how many bounded runs happened, the deadline each was given, and how much
// of it the stub reports as consumed (so a caller's remainder-sleep policy is observable).
int  sysexec_stub_count_deadline_calls(void);
unsigned sysexec_stub_deadline_ms(int index);
void sysexec_stub_set_deadline_elapsed(unsigned ms);

#endif
