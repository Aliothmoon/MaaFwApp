/*
 * libagentexec.so <parent-pid> <executable> [args...]
 *
 * ProcessBuilder 在 fork 与 exec 之间插不进代码，先 exec 到这里登记 PDEATHSIG 再 exec 真正的 agent，
 * pid 与管道不变。特权进程被 SIGKILL 时 child 随之收走，不留孤儿抢下一轮 client 的端口
 *
 * PDEATHSIG 认的是 fork 它的那条线程，线程退出同样触发，所以只能从常驻线程拉起
 * 出错写 stderr：ExecAgentHost 把它接进运行日志
 */
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <parent-pid> <executable> [args...]\n", argv[0]);
        return 2;
    }
    char *end = NULL;
    long parent = strtol(argv[1], &end, 10);
    if (end == argv[1] || *end != '\0' || parent <= 0) {
        fprintf(stderr, "agentexec: bad parent pid: %s\n", argv[1]);
        return 2;
    }

    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
        fprintf(stderr, "agentexec: prctl(PR_SET_PDEATHSIG) failed: %s\n", strerror(errno));
        return 1;
    }
    // 父进程死在 fork 与 prctl 之间时信号不会再来，此刻已被 init 收养
    if (getppid() != (pid_t) parent) {
        fprintf(stderr, "agentexec: parent %ld gone before exec\n", parent);
        return 1;
    }

    execv(argv[2], &argv[2]);
    fprintf(stderr, "agentexec: execv(%s) failed: %s\n", argv[2], strerror(errno));
    return 127;
}
