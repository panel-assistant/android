// Compile-time paths only for the standalone dispatch fixture, never the Android daemon.
#ifndef ZIGBEE_TEST_ROOT
#error ZIGBEE_TEST_ROOT must name the target private build fixture
#endif
#define ZIGBEE_DIR ZIGBEE_TEST_ROOT "/vendor"
#define ZIGBEE_DATA ZIGBEE_TEST_ROOT "/data"
#define ZIGBEE_PROC ZIGBEE_TEST_ROOT "/proc"
#define ZIGBEE_SH ZIGBEE_TEST_ROOT "/sh"
