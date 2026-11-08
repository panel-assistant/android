#ifndef HELPER_FUZZ_SANDBOX_H
#define HELPER_FUZZ_SANDBOX_H
void fuzz_sandbox_init(void);
void fuzz_sandbox_reset(void);
void fuzz_sandbox_enter(void);
void fuzz_sandbox_leave(void);
const char *fuzz_sandbox_root(void);
#endif
