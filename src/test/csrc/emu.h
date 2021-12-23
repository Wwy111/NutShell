#include <cstdlib>
#include <cassert>
#include <iostream>
#include <iomanip>
#include <fstream>
#include <vector>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "difftest.h"

//#include "VSimTop__Dpi.h"
#include "common.h"
#include "VNutShellSimTop.h"
#include "doublylinkedlist.h"
#if VM_TRACE
#include <verilated_vcd_c.h>	// Trace file format header
#endif


class Emulator {
  const char *image;
  std::shared_ptr<VNutShellSimTop> dut_ptr;
#if VM_TRACE
  VerilatedVcdC* tfp;
#endif

  // emu control variable
  uint32_t seed;
  uint64_t max_cycles, cycles;
  uint64_t log_begin, log_end, log_level;
  uint64_t  id;

  std::vector<const char *> parse_args(int argc, const char *argv[]);

  static const struct option long_options[];
  static void print_help(const char *file);

  void read_emu_regs(rtlreg_t *r) {
#define macro(x) r[x] = dut_ptr->io_difftest_r_##x
    macro(0); macro(1); macro(2); macro(3); macro(4); macro(5); macro(6); macro(7);
    macro(8); macro(9); macro(10); macro(11); macro(12); macro(13); macro(14); macro(15);
    macro(16); macro(17); macro(18); macro(19); macro(20); macro(21); macro(22); macro(23);
    macro(24); macro(25); macro(26); macro(27); macro(28); macro(29); macro(30); macro(31);
    r[DIFFTEST_THIS_PC] = dut_ptr->io_difftest_thisPC;
#ifndef __RV32__
    r[DIFFTEST_MSTATUS] = dut_ptr->io_difftest_mstatus;
    r[DIFFTEST_SSTATUS] = dut_ptr->io_difftest_sstatus;
    r[DIFFTEST_MEPC   ] = dut_ptr->io_difftest_mepc;
    r[DIFFTEST_SEPC   ] = dut_ptr->io_difftest_sepc;
    r[DIFFTEST_MCAUSE ] = dut_ptr->io_difftest_mcause;
    r[DIFFTEST_SCAUSE ] = dut_ptr->io_difftest_scause;
#endif
  }

  public:
  // argv decay to the secondary pointer
  Emulator(int argc, const char *argv[]):
    image(nullptr),
    dut_ptr(new std::remove_reference<decltype(*dut_ptr)>::type),
    seed(0), max_cycles(-1), cycles(0),
    log_begin(0), log_end(-1), log_level(LOG_ALL), id(0)
  {
    // init emu
    auto args = parse_args(argc, argv);
    printf("id : %d\n", id);

    // srand
    srand(seed);
    srand48(seed);
    Verilated::randReset(2);

    // set log time range and log level
    dut_ptr->io_logCtrl_log_begin = log_begin;
    dut_ptr->io_logCtrl_log_end = log_end;
    dut_ptr->io_logCtrl_log_level = log_level;

    // init ram
    extern void init_ram(const char *img);
    init_ram(image);

    // init device
    extern void init_device(void);
    init_device();

    // init core
    reset_ncycles(10);
  }

  void reset_ncycles(size_t cycles) {
    for(int i = 0; i < cycles; i++) {
      dut_ptr->reset = 1;
      dut_ptr->clock = 0;
      dut_ptr->eval();
      dut_ptr->clock = 1;
      dut_ptr->eval();
      dut_ptr->reset = 0;
    }
  }

  void cfi_check(void) {
    int i;

    if(dut_ptr->io_difftest_thisPC == 0x80000004 || dut_ptr->io_difftest_thisPC == 0x800046c0) {     // device 2 request tu device 1
      dut_ptr->io_cfi_in_valid = 1;
      dut_ptr->io_cfi_in_id = 4;
      dut_ptr->io_cfi_in_cmd = 3;
      dut_ptr->io_cfi_in_srcAddr = 0x80004c28;
      dut_ptr->io_cfi_in_dstAddr = 0x80004e30;
      printf("pc is %lx\n", dut_ptr->io_difftest_thisPC);
      return;
    }

    if(dut_ptr->io_cfi_out_valid == 1 && dut_ptr->io_cfi_out_id == 4) {
      dut_ptr->io_cfi_in_valid = 0;
      printf("cmd : %d\n", dut_ptr->io_cfi_out_cmd);
      printf("src : %lx, dst : %lx\n", dut_ptr->io_cfi_out_srcAddr, dut_ptr->io_cfi_out_dstAddr);
      return;
    }

    if(dut_ptr->io_cfi_out_valid == 0) {
      dut_ptr->io_cfi_in_valid = 0;
      return;
    }
    else {
      dut_ptr->io_cfi_in_valid = 1;

      if(dut_ptr->io_cfi_out_cmd == 3){         // lookupIoT
        dut_ptr->io_cfi_in_valid = 0;

        dut_ptr->io_cfi_in_id = dut_ptr->io_cfi_out_id;
        dut_ptr->io_cfi_in_cmd = 1;             // lookupfail
        return;
      }
      else if(dut_ptr->io_cfi_out_cmd == 4){    // lookupCloud
        cfglink p = search(dut_ptr->io_cfi_out_srcAddr);
        
        for(i = 0; i < p->destNum; i++) {
          if(p->dest[i] == dut_ptr->io_cfi_out_dstAddr) {
            dut_ptr->io_cfi_in_id = dut_ptr->io_cfi_out_id;
            dut_ptr->io_cfi_in_cmd = 0;         // patch
            dut_ptr->io_cfi_in_srcAddr = dut_ptr->io_cfi_out_srcAddr;
            dut_ptr->io_cfi_in_dstAddr = dut_ptr->io_cfi_out_dstAddr;
            return;
          }
        }
        if(i == p->destNum) {
          dut_ptr->io_cfi_in_cmd = 2;
          return;
        }
      }
    }
  }

  void cfi_check_file() {
    FILE *fp1, *fp2, *fp3, *fp4;
    const char cfi_1_req[30]  = "/home/wwy/cfi/cfi_1_req.txt";
    const char cfi_1_resp[30] = "/home/wwy/cfi/cfi_1_resp.txt";
    const char cfi_2_req[30]  = "/home/wwy/cfi/cfi_2_req.txt";
    const char cfi_2_resp[30] = "/home/wwy/cfi/cfi_2_resp.txt";

    static uint8_t  last_valid;
    static uint64_t last_cmd;

    if(id == 1) {
      if(dut_ptr->io_cfi_out_valid == 1 && (last_cmd != dut_ptr->io_cfi_out_cmd || last_valid == 0)) {      
        if(dut_ptr->io_cfi_out_id == id) {          // req
          if ((fp1 = fopen(cfi_1_req, "w")) == NULL) {
		        printf("error open /home/wwy/cfi/cfi_1_req.txt!\n");
		        exit(1);
	        }
          fprintf(fp1, "VALID\n");
          fprintf(fp1, "%d\n", dut_ptr->io_cfi_out_valid);
          fprintf(fp1, "%d\n", dut_ptr->io_cfi_out_id);
          fprintf(fp1, "%d\n", dut_ptr->io_cfi_out_cmd);
          fprintf(fp1, "%lx\n", dut_ptr->io_cfi_out_srcAddr);
          fprintf(fp1, "%lx\n", dut_ptr->io_cfi_out_dstAddr);
          
          fclose(fp1);
        }
        else if(dut_ptr->io_cfi_out_id != id) {     // resp to 2
          if ((fp4 = fopen(cfi_2_resp, "w")) == NULL) {
		        printf("error open /home/wwy/cfi/cfi_2_resp.txt!\n");
		        exit(1);
	        }
          fprintf(fp4, "VALID\n");
          fprintf(fp4, "%d\n", dut_ptr->io_cfi_out_valid);
          fprintf(fp4, "%d\n", dut_ptr->io_cfi_out_id);
          fprintf(fp4, "%d\n", dut_ptr->io_cfi_out_cmd);
          fprintf(fp4, "%lx\n", dut_ptr->io_cfi_out_srcAddr);
          fprintf(fp4, "%lx\n", dut_ptr->io_cfi_out_dstAddr);
          
          fclose(fp4);
        }
      }
      if ((fp2 = fopen(cfi_1_resp, "r")) == NULL) {
		    printf("error open /home/wwy/cfi/cfi_1_resp.txt!\n");
		    exit(1);
	    }
      if ((fp3 = fopen(cfi_2_req, "r")) == NULL) {
		    printf("error open /home/wwy/cfi/cfi_2_req.txt!\n");
		    exit(1);
	    }
      char valid_1_resp[10], valid_2_req[10];
      fscanf(fp2, "%s", valid_1_resp);
      fscanf(fp3, "%s", valid_2_req);
      if(strcmp(valid_1_resp, "VALID") == 0) {    // if both valid, process resp
        fscanf(fp2, "%d", &dut_ptr->io_cfi_in_valid);
        fscanf(fp2, "%d", &dut_ptr->io_cfi_in_id);
        fscanf(fp2, "%d", &dut_ptr->io_cfi_in_cmd);
        fscanf(fp2, "%lx", &dut_ptr->io_cfi_in_srcAddr);
        fscanf(fp2, "%lx", &dut_ptr->io_cfi_in_dstAddr);
        
        fclose(fp2);
        fclose(fp3);
        if ((fp2 = fopen(cfi_1_resp, "w")) == NULL) {                     // clear
		      printf("error open /home/wwy/cfi/cfi_1_resp.txt!\n");
		      exit(1);
	      }
        fclose(fp2);
      }
      else if(strcmp(valid_2_req, "VALID") == 0) {
        fscanf(fp3, "%d", &dut_ptr->io_cfi_in_valid);
        fscanf(fp3, "%d", &dut_ptr->io_cfi_in_id);
        fscanf(fp3, "%d", &dut_ptr->io_cfi_in_cmd);
        fscanf(fp3, "%lx", &dut_ptr->io_cfi_in_srcAddr);
        fscanf(fp3, "%lx", &dut_ptr->io_cfi_in_dstAddr);
        
        fclose(fp2);
        fclose(fp3);
        if(dut_ptr->io_cfi_in_cmd == 3) {
          if ((fp3 = fopen(cfi_2_req, "w")) == NULL) {                     // clear
		        printf("error open /home/wwy/cfi/cfi_2_req.txt!\n");
		        exit(1);
	        }
          fclose(fp3);
        }
      }
      else {
        dut_ptr->io_cfi_in_valid = 0;
        fclose(fp2);
        fclose(fp3);
      }
    }
    else if(id == 2) {
      if(dut_ptr->io_cfi_out_valid == 1 && (last_cmd != dut_ptr->io_cfi_out_cmd || last_valid == 0)) {      
        if(dut_ptr->io_cfi_out_id == id) {                     // req
          if ((fp3 = fopen(cfi_2_req, "w")) == NULL) {
		        printf("error open /home/wwy/cfi/cfi_2_req.txt!\n");
		        exit(1);
	        }
          fprintf(fp3, "VALID\n");
          fprintf(fp3, "%d\n", dut_ptr->io_cfi_out_valid);
          fprintf(fp3, "%d\n", dut_ptr->io_cfi_out_id);
          fprintf(fp3, "%d\n", dut_ptr->io_cfi_out_cmd);
          fprintf(fp3, "%lx\n", dut_ptr->io_cfi_out_srcAddr);
          fprintf(fp3, "%lx\n", dut_ptr->io_cfi_out_dstAddr);
          
          fclose(fp3);
        }
        else if(dut_ptr->io_cfi_out_id != id) {     // resp to 2
          if ((fp2 = fopen(cfi_1_resp, "w")) == NULL) {
		        printf("error open /home/wwy/cfi/cfi_1_resp.txt!\n");
		        exit(1);
	        }
          fprintf(fp2, "VALID\n");
          fprintf(fp2, "%d\n", dut_ptr->io_cfi_out_valid);
          fprintf(fp2, "%d\n", dut_ptr->io_cfi_out_id);
          fprintf(fp2, "%d\n", dut_ptr->io_cfi_out_cmd);
          fprintf(fp2, "%lx\n", dut_ptr->io_cfi_out_srcAddr);
          fprintf(fp2, "%lx\n", dut_ptr->io_cfi_out_dstAddr);
          
          fclose(fp2);
        }
      }
      if ((fp4 = fopen(cfi_2_resp, "r")) == NULL) {
		    printf("error open /home/wwy/cfi/cfi_2_resp.txt!\n");
		    exit(1);
	    }
      if ((fp1 = fopen(cfi_1_req, "r")) == NULL) {
		    printf("error open /home/wwy/cfi/cfi_1_req.txt!\n");
		    exit(1);
	    }
      char valid_2_resp[10], valid_1_req[10];
      fscanf(fp4, "%s", valid_2_resp);
      fscanf(fp1, "%s", valid_1_req);
      if(strcmp(valid_2_resp, "VALID") == 0) {    // if both valid, process resp
        fscanf(fp4, "%d", &dut_ptr->io_cfi_in_valid);
        fscanf(fp4, "%d", &dut_ptr->io_cfi_in_id);
        fscanf(fp4, "%d", &dut_ptr->io_cfi_in_cmd);
        fscanf(fp4, "%lx", &dut_ptr->io_cfi_in_srcAddr);
        fscanf(fp4, "%lx", &dut_ptr->io_cfi_in_dstAddr);
        
        fclose(fp4);
        fclose(fp1);
        if ((fp4 = fopen(cfi_2_resp, "w")) == NULL) {                     // clear
		      printf("error open /home/wwy/cfi/cfi_2_resp.txt!\n");
		      exit(1);
	      }
        fclose(fp4);
      }
      else if(strcmp(valid_1_req, "VALID") == 0) {
        fscanf(fp1, "%d", &dut_ptr->io_cfi_in_valid);
        fscanf(fp1, "%d", &dut_ptr->io_cfi_in_id);
        fscanf(fp1, "%d", &dut_ptr->io_cfi_in_cmd);
        fscanf(fp1, "%lx", &dut_ptr->io_cfi_in_srcAddr);
        fscanf(fp1, "%lx", &dut_ptr->io_cfi_in_dstAddr);
        
        fclose(fp4);
        fclose(fp1);
        if(dut_ptr->io_cfi_in_cmd == 3) {
          if ((fp1 = fopen(cfi_1_req, "w")) == NULL) {                     // clear
		        printf("error open /home/wwy/cfi/cfi_1_req.txt!\n");
		        exit(1);
	        }
          fclose(fp1);
        }
      }
      else {
        dut_ptr->io_cfi_in_valid = 0;
        fclose(fp4);
        fclose(fp1);
      }
    }

    last_valid = dut_ptr->io_cfi_out_valid;
    last_cmd = dut_ptr->io_cfi_out_cmd;
  }

  void single_cycle() {
    if(dut_ptr->io_difftest_thisPC > 0x80000010) {
      cfi_check_file();
    }
    // cfi_check();
    dut_ptr->clock = 0;
    dut_ptr->eval();

    dut_ptr->clock = 1;
    dut_ptr->eval();
    if(dut_ptr->io_cfi_in_cmd == 2) {          // stop the machine
      dut_ptr->reset = 1;
    }

#if VM_TRACE
    tfp->dump(cycles);
#endif

    cycles ++;

  }

  void execute_cycles(uint64_t n) {
    extern bool is_finish();
    extern void poll_event(void);
    extern uint32_t uptime(void);
    extern void set_abort(void);
    uint32_t lasttime = 0;
    uint64_t lastcommit = n;
    int hascommit = 0;
    const int stuck_limit = 2000;

#if VM_TRACE
    Verilated::traceEverOn(true);	// Verilator must compute traced signals
    VL_PRINTF("Enabling waves...\n");
    tfp = new VerilatedVcdC;
    dut_ptr->trace(tfp, 99);	// Trace 99 levels of hierarchy
    tfp->open("vlt_dump.vcd");	// Open the dump file
#endif

    while (!is_finish() && n > 0) {
      single_cycle();
      n --;

//       if (lastcommit - n > stuck_limit && hascommit) {
//         eprintf("No instruction commits for %d cycles, maybe get stuck\n"
//             "(please also check whether a fence.i instruction requires more than %d cycles to flush the icache)\n",
//             stuck_limit, stuck_limit);
// #if VM_TRACE
//         tfp->close();
// #endif
//         set_abort();
//       }

      if (!hascommit && (uint32_t)dut_ptr->io_difftest_thisPC == 0x80000000) {
        hascommit = 1;
        extern void init_difftest(rtlreg_t *reg);
        rtlreg_t reg[DIFFTEST_NR_REG];
        read_emu_regs(reg);
        init_difftest(reg);
      }

      // difftest
      if (dut_ptr->io_difftest_commit && hascommit) {
        rtlreg_t reg[DIFFTEST_NR_REG];
        read_emu_regs(reg);

        extern int difftest_step(rtlreg_t *reg_scala, uint32_t this_inst,
          int isMMIO, int isRVC, int isRVC2, uint64_t intrNO, int priviledgeMode, int isMultiCommit);
        if (dut_ptr->io_difftestCtrl_enable) {
          if (difftest_step(reg, dut_ptr->io_difftest_thisINST,
              dut_ptr->io_difftest_isMMIO, dut_ptr->io_difftest_isRVC, dut_ptr->io_difftest_isRVC2,
              dut_ptr->io_difftest_intrNO, dut_ptr->io_difftest_priviledgeMode, 
              dut_ptr->io_difftest_isMultiCommit)) {
#if VM_TRACE
            tfp->close();
#endif
            set_abort();
          }
        }
        lastcommit = n;
      }

      uint32_t t = uptime();
      if (t - lasttime > 100) {
        poll_event();
        lasttime = t;
      }
    }
  }

  void cache_test(uint64_t n) {
    while (n > 0) {
      single_cycle();
      n --;
    }
  }

  void execute() {
//#define CACHE_TEST

#ifdef CACHE_TEST
    eprintf(ANSI_COLOR_MAGENTA "This is random test for cache.\n" ANSI_COLOR_RESET);
    cache_test(max_cycles);
#else
    execute_cycles(max_cycles);
#endif
  }
  uint64_t get_cycles() const { return cycles; }
  uint64_t get_max_cycles() const { return max_cycles; }
};
