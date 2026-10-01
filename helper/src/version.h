#ifndef HAPANELD_VERSION_H
#define HAPANELD_VERSION_H

#include "cmd.h"

/*
 * VERSION is the helper's stable bootstrap contract. Protocol minor revisions are additive; a
 * protocol major change means the client must not claim compatibility.
 */
#define HAPANELD_HELPER_VERSION "1.3.1"
#define HAPANELD_HELPER_PROTOCOL_MAJOR 1
#define HAPANELD_HELPER_PROTOCOL_MINOR 3

const char *helper_identity(void);
const char *helper_build_id(void);
const char *helper_build_id_record(void);
void cmd_version(conn_ctx *ctx, const char *args);

#endif
