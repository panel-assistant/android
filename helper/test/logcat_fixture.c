#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

int main(int argc, char **argv) {
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
