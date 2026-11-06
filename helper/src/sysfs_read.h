#ifndef HAPANELD_SYSFS_READ_H
#define HAPANELD_SYSFS_READ_H
#include "cmd.h"
void cmd_relayread(conn_ctx *ctx, const char *args);
void cmd_gpioread(conn_ctx *ctx, const char *args);
#endif
