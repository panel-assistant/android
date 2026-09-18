// The app identity table. Pure data plus exact lookups — no stat, no exec, so it stays host-native
// testable and the peer-authentication policy that DOES stat can keep living in main.c, where the
// white-box regression test replaces stat(2) alone.
#include "identity.h"

#include <string.h>

const helper_app_package HELPER_APP_PACKAGES[HELPER_APP_PACKAGE_COUNT] = {
    { HELPER_CALLER_LEGACY,    "io.github.maxlyth.hapaneld", APP_DATA,           APP_USER_DATA },
    { HELPER_CALLER_SUCCESSOR, "io.panelassistant.android",  APP_DATA_SUCCESSOR, APP_USER_DATA_SUCCESSOR },
};

const helper_app_package *helper_app_package_for(enum helper_caller caller) {
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++)
        if (HELPER_APP_PACKAGES[i].caller == caller) return &HELPER_APP_PACKAGES[i];
    return NULL;
}

enum helper_caller helper_caller_for_package(const char *package) {
    if (!package || !*package) return HELPER_CALLER_NONE;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++)
        if (strcmp(package, HELPER_APP_PACKAGES[i].package) == 0) return HELPER_APP_PACKAGES[i].caller;
    return HELPER_CALLER_NONE;
}

int helper_known_package(const char *package) {
    return helper_caller_for_package(package) != HELPER_CALLER_NONE;
}

const char *helper_caller_name(enum helper_caller caller) {
    switch (caller) {
        case HELPER_CALLER_ROOT:      return "ROOT";
        case HELPER_CALLER_LEGACY:    return "LEGACY";
        case HELPER_CALLER_SUCCESSOR: return "SUCCESSOR";
        case HELPER_CALLER_NONE:      break;
    }
    return "NONE";
}
