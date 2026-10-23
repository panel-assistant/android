// Room temperature + humidity read verb for the exact supported CHT8305-compatible layouts.
//
// Three vendor routes are supported, tried in order:
//   1. evdev: TPA10's CHT8305 and ZX-SMT156's GXHT30 vendor drivers report readings through the input
//      subsystem as EV_ABS centi-units on root/input-owned nodes, point-read via EVIOCGABS.
//   2. sysfs hwmon: a standard Linux hwmon instance exposing temp1_input + humidity1_input (milli-units).
//   3. sysfs direct: the Smatek S9E's CHT8305-class sensor driver registers NO hwmon humidity node, so the
//      reading is point-read from its fixed device-tree path (i2c 1-0040, milli-units) instead.
// The app cannot read these root-owned nodes, so the value comes through the helper. cmd_cht8305 point-reads
// the current values (no wait for the next event) and replies in the same centi-unit contract below.
#ifndef HAPANELD_CHT8305_H
#define HAPANELD_CHT8305_H

#include "cmd.h"

// CHT8305 — reply "T=<centi> H=<centi>\n" (raw hundredths of a degree / %RH) or "ERR\n" when either
// input is absent/unreadable or exact-name discovery is ambiguous. The app divides by 100 and applies
// the calibration offset.
void cmd_cht8305(conn_ctx *ctx, const char *args);

#ifdef HAPANELD_TEST
#include <stddef.h>
size_t cht8305_test_candidate_count(void);
int cht8305_test_event_name(const char *name);
int cht8305_test_candidate(size_t index, const char **temp_name, unsigned int *temp_axis,
                           const char **humidity_name, unsigned int *humidity_axis);

struct cht8305_test_input {
    const char *name;
    int readable;
    long value;
};

int cht8305_test_resolve(const struct cht8305_test_input *inputs, size_t count,
                         long *temp, long *humidity);

// One hwmon instance's climate readings, as read from `/sys/class/hwmon/hwmonN`. `has_temp`/`has_humidity`
// model whether each `*_input` node was present and parseable (milli-units); absent/garbage reads clear them.
struct cht8305_test_hwmon {
    int has_temp;
    long temp_milli;
    int has_humidity;
    long humidity_milli;
};

// Resolve the same coherent-pair / fail-closed rules the live hwmon scan applies, then convert the winning
// instance's milli-units to the centi-unit reply contract. Returns 1 with *temp/*humidity in centi-units
// on a single unambiguous full pair, 0 otherwise (no pair, a partial instance, or two full pairs).
int cht8305_test_resolve_hwmon(const struct cht8305_test_hwmon *instances, size_t count,
                               long *temp, long *humidity);

// The direct device-tree route (S9E): convert a point-read temp+humidity pair to centi-units, requiring
// both leaves present (has_temp && has_humidity) exactly as the live read does. 1 on a full pair, else 0.
int cht8305_test_resolve_direct(int has_temp, long temp_milli, int has_humidity, long humidity_milli,
                                long *temp, long *humidity);
#endif

#endif
