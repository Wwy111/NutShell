#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <stdint.h>

int main(void)
{
	pid_t pidA, pidB;    // two IoT system
    int fd_AWBR[2], fd_ARBW[2];           // pipe for process communicate

    if(pipe(fd_AWBR) < 0 || pipe(fd_ARBW) < 0) {
        perror("pipe failed");
        exit(1);
    }
    pidA = fork();
    if(pidA < 0) {
        perror("fork A failed");
        exit(1);
    }
    else if(pidA > 0) {
        pidB = fork();
        if(pidB < 0) {
            perror("fork B failed");
            exit(1);
        }
    }

    if(pidA == 0) {
        printf("son process A pid is %d, father process os %d\n", getpid(), getppid());
        // execlp("make", "make", "emu", "IMAGE=./ready-to-run/microbench-riscv64-nutshell.bin", NULL);
        close(fd_AWBR[0]);
        close(fd_ARBW[1]);
        uint8_t matA[2][2] = {{0, 1}, {2, 3}};
        uint8_t req;
        int n = read(fd_ARBW[0], &req, 1);
        if(req == 1){
            write(fd_AWBR[1], matA, sizeof(matA));
        }
        // write(fd[1], "hello wwy\n", sizeof("hello wwy\n"));
    }
    if(pidB == 0) {
        printf("son process B pid is %d, father process os %d\n", getpid(), getppid());
        // execlp("make", "make", "emu", "IMAGE=./ready-to-run/microbench-riscv64-nutshell.bin", NULL);
        close(fd_AWBR[1]);
        close(fd_ARBW[0]);
        uint8_t matB[2][2];
        uint8_t req = 1;
        write(fd_ARBW[1], &req, 1);
        int n = read(fd_AWBR[0], matB, sizeof(matB));
        for(int i = 0; i < 2; i++) {
            for(int j = 0; j < 2; j++) {
                printf("%d ",matB[i][j]);
            }
            printf("\n");
        }
        // write(STDOUT_FILENO, matB, n);
        // int n = read(fd[0], line, 20);
        // write(STDOUT_FILENO, line, n);
        // printf("%s", line);
    }

    if(pidA > 0 && pidB > 0) {
        waitpid(pidA, NULL, 0);
        waitpid(pidB, NULL, 0);
        printf("Father process pid is %d\n", getpid());
    }
	return 0;
}