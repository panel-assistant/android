// The shared app identity table and its exact lookups. Presence checks use both data addresses;
// peer-credential authentication remains in main.c.
#include "identity.h"
#include <errno.h>
#include <sys/stat.h>
#include <string.h>

int helper_app_data_absent(void) {
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++) {
        const char *paths[] = { HELPER_APP_PACKAGES[i].data_dir, HELPER_APP_PACKAGES[i].user_dir };
        for (size_t j = 0; j < 2; j++) {
            struct stat st;
            if (lstat(paths[j], &st) == 0 || errno != ENOENT) return 0;
        }
    }
    return 1;
}

const helper_app_package HELPER_APP_PACKAGES[HELPER_APP_PACKAGE_COUNT] = {
    { HELPER_CALLER_SUCCESSOR, "io.panelassistant.android", APP_DATA, APP_USER_DATA },
};

const helper_app_package *helper_app_package_for(enum helper_caller caller) {
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++)
        if (HELPER_APP_PACKAGES[i].caller == caller) return &HELPER_APP_PACKAGES[i];
    return NULL;
}

int helper_known_package(const char *package) {
    if (!package || !*package) return 0;
    for (size_t i = 0; i < HELPER_APP_PACKAGE_COUNT; i++)
        if (strcmp(package, HELPER_APP_PACKAGES[i].package) == 0) return 1;
    return 0;
}
