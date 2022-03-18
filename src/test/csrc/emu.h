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

#define DEVICE_NUM 3


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

  // void cfi_check(void) {
  //   int i;

  //   if(dut_ptr->io_difftest_thisPC == 0x80000004 || dut_ptr->io_difftest_thisPC == 0x800046c0) {     // device 2 request tu device 1
  //     dut_ptr->io_cfi_in_valid = 1;
  //     dut_ptr->io_cfi_in_id = 4;
  //     dut_ptr->io_cfi_in_cmd = 3;
  //     dut_ptr->io_cfi_in_srcAddr = 0x80004c28;
  //     dut_ptr->io_cfi_in_dstAddr = 0x80004e30;
  //     printf("pc is %lx\n", dut_ptr->io_difftest_thisPC);
  //     return;
  //   }

  //   if(dut_ptr->io_cfi_out_valid == 1 && dut_ptr->io_cfi_out_id == 4) {
  //     dut_ptr->io_cfi_in_valid = 0;
  //     printf("cmd : %d\n", dut_ptr->io_cfi_out_cmd);
  //     printf("src : %lx, dst : %lx\n", dut_ptr->io_cfi_out_srcAddr, dut_ptr->io_cfi_out_dstAddr);
  //     return;
  //   }

  //   if(dut_ptr->io_cfi_out_valid == 0) {
  //     dut_ptr->io_cfi_in_valid = 0;
  //     return;
  //   }
  //   else {
  //     dut_ptr->io_cfi_in_valid = 1;

  //     if(dut_ptr->io_cfi_out_cmd == 3){         // lookupIoT
  //       dut_ptr->io_cfi_in_valid = 0;

  //       dut_ptr->io_cfi_in_id = dut_ptr->io_cfi_out_id;
  //       dut_ptr->io_cfi_in_cmd = 1;             // lookupfail
  //       return;
  //     }
  //     else if(dut_ptr->io_cfi_out_cmd == 4){    // lookupCloud
  //       cfglink p = search(dut_ptr->io_cfi_out_srcAddr);
        
  //       for(i = 0; i < p->destNum; i++) {
  //         if(p->dest[i] == dut_ptr->io_cfi_out_dstAddr) {
  //           dut_ptr->io_cfi_in_id = dut_ptr->io_cfi_out_id;
  //           dut_ptr->io_cfi_in_cmd = 0;         // patch
  //           dut_ptr->io_cfi_in_srcAddr = dut_ptr->io_cfi_out_srcAddr;
  //           dut_ptr->io_cfi_in_dstAddr = dut_ptr->io_cfi_out_dstAddr;
  //           return;
  //         }
  //       }
  //       if(i == p->destNum) {
  //         dut_ptr->io_cfi_in_cmd = 2;
  //         return;
  //       }
  //     }
  //   }
  // }

  // const char *cfi_req_file[DEVICE_NUM] = {"/home/wwy/cfi/cfi_1_req.txt",
  //                                         "/home/wwy/cfi/cfi_2_req.txt",
  //                                         "/home/wwy/cfi/cfi_3_req.txt"};
  // const char *cfi_resp_file[DEVICE_NUM] = {"/home/wwy/cfi/cfi_1_resp.txt",
  //                                          "/home/wwy/cfi/cfi_2_resp.txt",
  //                                          "/home/wwy/cfi/cfi_3_resp.txt"};

  // void cfi_write_file(uint64_t write_id, uint8_t re) {     //re 0 : req, 1 : resp 
  //   FILE *fp;

  //   if(re != 0 && re != 1) {
  //     printf("error!!!!!\n");
  //     assert(0);
  //   }

  //   if(re == 0) {
  //     if((fp = fopen(cfi_req_file[write_id-1], "w")) == NULL) {
  //       printf("error open %s\n", cfi_req_file[write_id-1]);
  //       assert(0);
  //     }
  //   }
  //   else {
  //     if((fp = fopen(cfi_resp_file[write_id-1], "w")) == NULL) {
  //       printf("error open %s\n", cfi_resp_file[write_id-1]);
  //       assert(0);
  //     }
  //   }


  //   fprintf(fp, "VALID\n");
  //   fprintf(fp, "%d\n", dut_ptr->io_cfi_out_valid);
  //   fprintf(fp, "%d\n", dut_ptr->io_cfi_out_id);
  //   fprintf(fp, "%d\n", dut_ptr->io_cfi_out_cmd);
  //   fprintf(fp, "%lx\n", dut_ptr->io_cfi_out_srcAddr);
  //   fprintf(fp, "%lx\n", dut_ptr->io_cfi_out_dstAddr);
          
  //   fclose(fp);
  // }

  // bool cfi_read_file(uint64_t read_id, uint8_t re) {   //re 0 : req, 1 : resp 
  //   FILE *fp;
  //   char VALID[10];

  //   if(re != 0 && re != 1) {
  //     printf("error!!!!!\n");
  //     assert(0);
  //   }

  //   if(re == 0) {
  //     if((fp = fopen(cfi_req_file[read_id-1], "r")) == NULL) {
  //       printf("error open %s\n", cfi_req_file[read_id-1]);
  //       assert(0);
  //     }
  //   }
  //   else {
  //     if((fp = fopen(cfi_resp_file[read_id-1], "r")) == NULL) {
  //       printf("error open %s\n", cfi_resp_file[read_id-1]);
  //       assert(0);
  //     }
  //   }

  //   fscanf(fp, "%s", VALID);
  //   if(strcmp(VALID, "VALID") == 0) {
  //     fscanf(fp, "%d", &dut_ptr->io_cfi_in_valid);
  //     fscanf(fp, "%d", &dut_ptr->io_cfi_in_id);
  //     fscanf(fp, "%d", &dut_ptr->io_cfi_in_cmd);
  //     fscanf(fp, "%lx", &dut_ptr->io_cfi_in_srcAddr);
  //     fscanf(fp, "%lx", &dut_ptr->io_cfi_in_dstAddr);
      
  //     fclose(fp);

  //     if(re == 1) {
  //       if((fp = fopen(cfi_resp_file[read_id-1], "w")) == NULL) {
  //         printf("error open %s\n", cfi_resp_file[read_id-1]);
  //         assert(0);
  //       }
  //       fclose(fp);
  //     }
  //     else if(dut_ptr->io_cfi_in_cmd == 3) {
  //       if((fp = fopen(cfi_req_file[read_id-1], "w")) == NULL) {
  //         printf("error open %s\n", cfi_req_file[read_id-1]);
  //         assert(0);
  //       }
  //       fclose(fp);
  //     }

  //     return true;
  //   }
  //   else {
  //     fclose(fp);
  //     return false;
  //   }

  // }

  // void cfi_check_file2() {
  //   static uint8_t  last_valid;
  //   static uint64_t last_cmd;

  //   // output
  //   if(dut_ptr->io_cfi_out_valid == 1 && (last_cmd != dut_ptr->io_cfi_out_cmd || last_valid == 0)) {
  //     if(dut_ptr->io_cfi_out_id == id) {           // write req
  //       cfi_write_file(dut_ptr->io_cfi_out_id, 0);
  //     }
  //     else {
  //       cfi_write_file(dut_ptr->io_cfi_out_id, 1); // write resp
  //     }
  //   } 

  //   // input
  //   if(id == 1) {
  //     if(cfi_read_file(id, 1) == false) {
  //       if(cfi_read_file(2, 0) == false) {
  //         if(cfi_read_file(3, 0) == false) {
  //           dut_ptr->io_cfi_in_valid = 0;
  //         }
  //       }
  //     }
  //   }
  //   else if(id == 2) {
  //     if(cfi_read_file(id, 1) == false) {
  //       if(cfi_read_file(1, 0) == false) {
  //         if(cfi_read_file(3, 0) == false) {
  //           dut_ptr->io_cfi_in_valid = 0;
  //         }
  //       }
  //     }
  //   }
  //   else if(id == 3) {
  //     if(cfi_read_file(id, 1) == false) {
  //       if(cfi_read_file(1, 0) == false) {
  //         if(cfi_read_file(2, 0) == false) {
  //           dut_ptr->io_cfi_in_valid = 0;
  //         }
  //       }
  //     }
  //   }

  //   last_valid = dut_ptr->io_cfi_out_valid;
  //   last_cmd = dut_ptr->io_cfi_out_cmd;
  // }

  void single_cycle() {
    // if(dut_ptr->io_difftest_thisPC > 0x80000010) {
    //   cfi_check_file2();
    // }
    // cfi_check();
    dut_ptr->clock = 0;
    dut_ptr->eval();

    dut_ptr->clock = 1;
    dut_ptr->eval();
    // if(dut_ptr->io_cfi_in_cmd == 2) {          // stop the machine
    //   dut_ptr->reset = 1;
    // }

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
