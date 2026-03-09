for i in $(seq 1 50); do dd if=/dev/urandom of=/data/data/com.dearmoon.shield/files/test_$i.enc bs=4096 count=4 2>/dev/null; done
