#include "cht8305.h"
#include "util.h"

#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

// Supported vendor climate drivers expose temperature and humidity as two separate input devices whose
// current values are the reading × 100. TPA10 uses "temperature" / "humidity" with ABS_THROTTLE for
// both. ZX-SMT156's GXHT30 driver uses "sun-ths" / "sun-hum", with ABS_THROTTLE for temperature and
// vendor axis 0x1d for humidity (GitHub #24 evidence, 2026-07-17). Read with EVIOCGABS — that returns the
// driver's last measurement immediately, so there's no blocking wait for the next event.
//
// The /dev/input/eventN number is NOT stable across boots (it depends on probe order), so match by the
// device name via EVIOCGNAME rather than a hardcoded node. Scan every node before selecting a pair: the
// helper must reject duplicate exact-name devices and mixtures of the two vendor layouts rather than
// silently combining whichever nodes happened to be visited first.

struct climate_input_pair {
    const char *temp_name;
    unsigned int temp_axis;
    const char *humidity_name;
    unsigned int humidity_axis;
};

// Exact allowlist only. Do not infer a climate sensor from arbitrary EV_ABS devices: a wrong match could
// publish touch, battery, or motion axes as room conditions.
static const struct climate_input_pair climate_inputs[] = {
    { "temperature", ABS_THROTTLE, "humidity", ABS_THROTTLE },
    { "sun-ths", ABS_THROTTLE, "sun-hum", 0x1d },
};

#define CLIMATE_INPUT_COUNT (sizeof climate_inputs / sizeof climate_inputs[0])

struct climate_reading {
    unsigned int matches;
    int readable;
    long value;
};

struct climate_pair_scan {
    struct climate_reading temp;
    struct climate_reading humidity;
};

struct climate_scan {
    struct climate_pair_scan pairs[CLIMATE_INPUT_COUNT];
};

typedef int (*climate_axis_reader)(void *opaque, unsigned int axis, long *out);

static void record_reading(struct climate_reading *reading, int readable, long value) {
    // Only the states zero, one, and ambiguous matter; saturating preserves fail-closed behaviour.
    if (reading->matches < 2) reading->matches++;
    if (reading->matches == 1 && readable) {
        reading->readable = 1;
        reading->value = value;
    }
}

static void observe_named_device(struct climate_scan *scan, const char *name,
                                 climate_axis_reader read_axis, void *opaque) {
    for (size_t i = 0; i < CLIMATE_INPUT_COUNT; i++) {
        const struct climate_input_pair *candidate = &climate_inputs[i];
        if (strcmp(name, candidate->temp_name) == 0) {
            long value = 0;
            int readable = read_axis(opaque, candidate->temp_axis, &value);
            record_reading(&scan->pairs[i].temp, readable, value);
        }
        if (strcmp(name, candidate->humidity_name) == 0) {
            long value = 0;
            int readable = read_axis(opaque, candidate->humidity_axis, &value);
            record_reading(&scan->pairs[i].humidity, readable, value);
        }
    }
}

static int resolve_climate_scan(const struct climate_scan *scan, long *temp, long *humidity) {
    int selected = -1;
    for (size_t i = 0; i < CLIMATE_INPUT_COUNT; i++) {
        const struct climate_pair_scan *pair = &scan->pairs[i];
        if (pair->temp.matches == 0 && pair->humidity.matches == 0) continue;

        // Any partial, unreadable, or duplicate layout makes the entire discovery ambiguous. This
        // also rejects a complete pair accompanied by one stray exact-name device from another pair.
        if (pair->temp.matches != 1 || pair->humidity.matches != 1 ||
            !pair->temp.readable || !pair->humidity.readable) return 0;
        if (selected >= 0) return 0;
        selected = (int)i;
    }
    if (selected < 0) return 0;

    *temp = scan->pairs[selected].temp.value;
    *humidity = scan->pairs[selected].humidity.value;
    return 1;
}

static int read_input_axis(void *opaque, unsigned int axis, long *out) {
    int fd = *(const int *)opaque;
    struct input_absinfo abs;
    if (ioctl(fd, EVIOCGABS(axis), &abs) < 0) return 0;
    *out = abs.value;
    return 1;
}

static int canonical_event_name(const char *name) {
    if (strncmp(name, "event", 5) != 0) return 0;
    const char *number = name + 5;
    size_t length = strlen(number);
    if (length < 1 || length > 3 || (length > 1 && number[0] == '0')) return 0;
    for (size_t i = 0; i < length; i++) {
        if (number[i] < '0' || number[i] > '9') return 0;
    }
    return 1;
}

static int scan_climate_inputs(long *temp, long *humidity) {
    DIR *d = opendir("/dev/input");
    if (!d) return 0;

    struct climate_scan scan = {0};
    struct dirent *e;
    char path[64], name[80];
    while ((e = readdir(d))) {
        if (!canonical_event_name(e->d_name)) continue;
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        name[0] = '\0';
        if (ioctl(fd, EVIOCGNAME(sizeof name), name) >= 0)
            observe_named_device(&scan, name, read_input_axis, &fd);
        close(fd);
    }
    closedir(d);
    return resolve_climate_scan(&scan, temp, humidity);
}

// Second supported route: a standard Linux hwmon instance. The Smatek S9E's CHT8305-class sensor is not an
// evdev device; its vendor driver exposes the reading at
// `/sys/devices/platform/twi.1/i2c-1/1-0040/{temp1_input,humidity1_input}`, which an in-tree hwmon driver
// surfaces as `/sys/class/hwmon/hwmonN/{temp1_input,humidity1_input}` in milli-units (reading × 1000).
//
// hwmonN is not stable across boots (it depends on probe order), so scan every instance rather than pinning
// a number — exactly as the evdev route matches by name rather than a fixed eventN.

// A single hwmon instance's climate pair as observed from sysfs; milli-units, with presence flags so a
// missing or unparseable `*_input` node is modelled as absence rather than a zero reading.
struct hwmon_reading {
    int has_temp;
    long temp_milli;
    int has_humidity;
    long humidity_milli;
};

// Convert the sole full pair to the centi-unit reply contract, applying the same fail-closed stance the
// evdev route uses. Unlike the evdev allowlist — where a bare exact-name device is a corrupt signal — a
// single-axis hwmon instance is the *common* case: RK3566 boards expose SoC/thermal zones as hwmon nodes
// that carry temp1_input and no humidity1_input. Those are skipped, not disqualifying; only a full temp+
// humidity pair is a candidate, and more than one full pair is ambiguous ⇒ fail. temp1_input/humidity1_input
// are milli-units and the protocol is centi-units, so centi = round(milli / 10) (round-half-away-from-zero).
static long milli_to_centi(long milli) {
    return milli >= 0 ? (milli + 5) / 10 : -((-milli + 5) / 10);
}

static int resolve_hwmon_climate(const struct hwmon_reading *readings, size_t count,
                                 long *temp, long *humidity) {
    int selected = -1;
    for (size_t i = 0; i < count; i++) {
        const struct hwmon_reading *r = &readings[i];
        // Only a coherent temp+humidity pair is a room-climate candidate. A temperature-only instance is a
        // thermal zone, not this sensor — skip it rather than letting it reject an otherwise-clear scan.
        if (!r->has_temp || !r->has_humidity) continue;
        if (selected >= 0) return 0;
        selected = (int)i;
    }
    if (selected < 0) return 0;
    *temp = milli_to_centi(readings[selected].temp_milli);
    *humidity = milli_to_centi(readings[selected].humidity_milli);
    return 1;
}

// Read one milli-unit integer from `<dir>/<leaf>`. Returns 1 and sets *out on a clean parse, 0 when the
// node is absent, empty, or not a bare integer (trailing garbage is rejected so a wrong node can't match).
static int read_hwmon_milli(const char *dir, const char *leaf, long *out) {
    char path[PATH_MAX];
    if (snprintf(path, sizeof path, "%s/%s", dir, leaf) >= (int)sizeof path) return 0;
    char buf[32];
    first_line(path, buf, sizeof buf);
    if (buf[0] == '\0') return 0;
    char *end = NULL;
    long value = strtol(buf, &end, 10);
    if (end == buf || *end != '\0') return 0;
    *out = value;
    return 1;
}

static int scan_hwmon_climate(long *temp, long *humidity) {
    DIR *d = opendir("/sys/class/hwmon");
    if (!d) return 0;

    // Bounded: a real device has a handful of hwmon instances. Capping keeps one pathological sysfs from
    // forcing an unbounded scan, and the cap is far above any real count so it never hides a true pair.
    enum { MAX_HWMON = 64 };
    struct hwmon_reading readings[MAX_HWMON];
    size_t count = 0;
    struct dirent *e;
    char dir[64];
    while ((e = readdir(d)) && count < MAX_HWMON) {
        if (strncmp(e->d_name, "hwmon", 5) != 0) continue;
        if (snprintf(dir, sizeof dir, "/sys/class/hwmon/%s", e->d_name) >= (int)sizeof dir) continue;
        struct hwmon_reading r = {0};
        r.has_temp = read_hwmon_milli(dir, "temp1_input", &r.temp_milli);
        r.has_humidity = read_hwmon_milli(dir, "humidity1_input", &r.humidity_milli);
        if (r.has_temp || r.has_humidity) readings[count++] = r;
    }
    closedir(d);
    return resolve_hwmon_climate(readings, count, temp, humidity);
}

// Third route: the sensor's fixed device-tree path, read directly. On the Smatek S9E the CHT8305-class
// sensor's in-tree driver does NOT register an hwmon instance carrying humidity (its only hwmon node is an
// unrelated `test_battery` that exposes temp1_input and no humidity1_input), so the generic hwmon scan finds
// no pair. The vendor exposes the reading at this stable i2c 1-0040 path instead (confirmed on live
// hardware: temp1_input/humidity1_input present, milli-units). Point-read both leaves; a full pair is
// required (same fail-closed stance as the other routes) and converts to the centi-unit reply contract.
#define S9E_CLIMATE_BASE "/sys/devices/platform/twi.1/i2c-1/1-0040"

// Shared by the live read and the test hook: a direct pair needs both leaves present, then converts to
// centi-units. No ambiguity dimension here — it is one fixed path, not a scan.
static int resolve_direct_climate(int has_temp, long temp_milli, int has_humidity, long humidity_milli,
                                  long *temp, long *humidity) {
    if (!has_temp || !has_humidity) return 0;
    *temp = milli_to_centi(temp_milli);
    *humidity = milli_to_centi(humidity_milli);
    return 1;
}

static int scan_direct_climate(long *temp, long *humidity) {
    long temp_milli = 0, humidity_milli = 0;
    int has_temp = read_hwmon_milli(S9E_CLIMATE_BASE, "temp1_input", &temp_milli);
    int has_humidity = read_hwmon_milli(S9E_CLIMATE_BASE, "humidity1_input", &humidity_milli);
    return resolve_direct_climate(has_temp, temp_milli, has_humidity, humidity_milli, temp, humidity);
}

#ifdef HAPANELD_TEST
size_t cht8305_test_candidate_count(void) {
    return CLIMATE_INPUT_COUNT;
}

int cht8305_test_event_name(const char *name) {
    return canonical_event_name(name);
}

int cht8305_test_candidate(size_t index, const char **temp_name, unsigned int *temp_axis,
                           const char **humidity_name, unsigned int *humidity_axis) {
    if (index >= cht8305_test_candidate_count()) return 0;
    const struct climate_input_pair *candidate = &climate_inputs[index];
    *temp_name = candidate->temp_name;
    *temp_axis = candidate->temp_axis;
    *humidity_name = candidate->humidity_name;
    *humidity_axis = candidate->humidity_axis;
    return 1;
}

struct test_axis_read {
    int readable;
    long value;
};

static int read_test_axis(void *opaque, unsigned int axis, long *out) {
    (void)axis;
    const struct test_axis_read *read = opaque;
    if (!read->readable) return 0;
    *out = read->value;
    return 1;
}

int cht8305_test_resolve(const struct cht8305_test_input *inputs, size_t count,
                         long *temp, long *humidity) {
    struct climate_scan scan = {0};
    for (size_t i = 0; i < count; i++) {
        struct test_axis_read read = { inputs[i].readable, inputs[i].value };
        observe_named_device(&scan, inputs[i].name, read_test_axis, &read);
    }
    return resolve_climate_scan(&scan, temp, humidity);
}

int cht8305_test_resolve_hwmon(const struct cht8305_test_hwmon *instances, size_t count,
                               long *temp, long *humidity) {
    struct hwmon_reading readings[64];
    if (count > sizeof readings / sizeof readings[0]) return 0;
    for (size_t i = 0; i < count; i++) {
        readings[i].has_temp = instances[i].has_temp;
        readings[i].temp_milli = instances[i].temp_milli;
        readings[i].has_humidity = instances[i].has_humidity;
        readings[i].humidity_milli = instances[i].humidity_milli;
    }
    return resolve_hwmon_climate(readings, count, temp, humidity);
}

int cht8305_test_resolve_direct(int has_temp, long temp_milli, int has_humidity, long humidity_milli,
                                long *temp, long *humidity) {
    return resolve_direct_climate(has_temp, temp_milli, has_humidity, humidity_milli, temp, humidity);
}
#endif

void cmd_cht8305(conn_ctx *ctx, const char *args) {
    (void)args;
    long t = 0, h = 0;
    // evdev first (TPA10 / ZX-SMT156), then the generic sysfs hwmon scan, then the S9E's fixed device-tree
    // path. All three yield the same centi-unit reply.
    if (scan_climate_inputs(&t, &h) || scan_hwmon_climate(&t, &h) || scan_direct_climate(&t, &h)) {
        char out[48];
        snprintf(out, sizeof out, "T=%ld H=%ld\n", t, h);
        reply(ctx->fd, out);
        return;
    }
    reply(ctx->fd, "ERR\n");
}
