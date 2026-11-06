#ifndef HAPANELD_ADB_RECOVERY_H
#define HAPANELD_ADB_RECOVERY_H
/* ARM/BEAT/RECOVER/SHELL remain exclusive to the separate administrator endpoint. */
/* Fixed ordinary ENSURE publishes the running helper and proves its recovery endpoint. */
int adb_recovery_ensure(void);
int adb_recovery_main(void);
#endif
