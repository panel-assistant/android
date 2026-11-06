#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// LOGCATDUMP shape: the fixed dump argv with the count last. The count selects the behaviour so
// one fixture can exercise a normal backlog, a wedged child and an unbounded producer.
static int dump(int argc, char **argv) {
    const char *expected[] = { "logcat", "-b", "all", "-v", "long", "-v", "epoch", "-v", "printable", "-d", "-t" };
    if (argc != 12) return 2;
    for (int i = 0; i < 11; i++) if (strcmp(argv[i], expected[i]) != 0) return 2;
    long count = strtol(argv[11], NULL, 10);
    if (count == 7) {                        // one complete entry, then wedge until killed
        puts("[ 1790592713.000 12: 13 I/Wedged ]\nbefore the wedge\n");
        fflush(stdout);
        for (;;) pause();
    }
    if (count == 999) {                      // endless output: the helper's byte cap must stop it
        for (;;) {
            fputs("[ 1790592713.000 12: 13 I/Flood ]\n", stdout);
            for (int j = 0; j < 1000; j++) putchar('F');
            fputs("\n\n", stdout);
        }
    }
    for (long i = 0; i < count; i++)
        printf("[ 1790592713.%03ld 12: 13 I/Backlog ]\nbacklog entry %ld\n\n", i, i);
    return 0;
}

int main(int argc, char **argv) {
    if (argc > 9 && strcmp(argv[9], "-d") == 0) return dump(argc, argv);
    const char *expected[] = { "logcat", "-b", "all", "-T", "1", "-v", "long", "-v", "epoch", "-v", "printable" };
    if (argc != 9 && argc != 11) return 2;
    for (int i = 0; i < argc; i++) if (strcmp(argv[i], expected[i]) != 0) return 2;
    int printable = argc == 11;
    long long stamp = (long long)time(NULL) + 1;
    printf("[ %lld.000 12: 13 I/Stale ]\nold buffered entry\n\n", stamp - 10);
    puts("--------- beginning of main");
    printf("[ %lld.000 12: 13 E/AndroidRuntime ]\n", stamp);
    if (printable) {
        puts("java.lang.Exception: complete\\n\\n at example.First.one(First.java:1)\\n at example.Second.two(Second.java:2)");
    } else {
        puts("java.lang.Exception: complete\n\n at example.First.one(First.java:1)\n at example.Second.two(Second.java:2)");
    }
    putchar('\n');
    printf("[ %lld.000 12: 13 E/Oversize ]\n", stamp);
    for (int i = 0; i < 9000; i++) putchar('X');
    puts("\n");
    for (int i = 0; i < 350; i++) {
        printf("[ %lld.000 12: 13 I/Burst ]\n", stamp);
        for (int j = 0; j < 1024; j++) putchar('A');
        puts("\n");
    }
    fflush(stdout);
    for (;;) pause();
}
