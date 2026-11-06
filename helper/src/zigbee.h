#ifndef HAPANELD_ZIGBEE_H
#define HAPANELD_ZIGBEE_H
#include "cmd.h"
void cmd_zigbeestate(conn_ctx *ctx, const char *args);
void cmd_zigbeehealth(conn_ctx *ctx, const char *args);
void cmd_zigbeerole(conn_ctx *ctx, const char *args);
#endif
