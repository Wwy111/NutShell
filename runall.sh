#!/bin/bash

ISA=riscv64
RISCVTEST_PATH=/home/wwy/am-kernels/tests/riscv-tests

echo "compiling riscv-test..."
cd $RISCVTEST_PATH
if make ARCH=$ISA-wwy &> /dev/null; then
  echo "riscv-test compile OK"
else
  echo "riscv-test compile error... exit..."
  exit
fi

files=`ls $RISCVTEST_PATH/build/*-$ISA-wwy.bin`
ori_log="build/wwy-log.txt"
cd /home/wwy/NutShell

for file in $files; do
  base=`basename $file | sed -e "s/-$ISA-wwy.bin//"`
  printf "[%14s] " $base
  logfile=$base-log.txt
  make emu IMAGE=$file &> $logfile

  if (grep 'HIT GOOD TRAP' $logfile > /dev/null) then
    echo -e "\033[1;32mPASS!\033[0m"
    rm $logfile
  else
    echo -e "\033[1;31mFAIL!\033[0m see $logfile for more information"
    if (test -e $ori_log) then
      echo -e "\n\n===== the original log.txt =====\n" >> $logfile
      cat $ori_log >> $logfile
    fi
  fi
done