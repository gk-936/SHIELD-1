import os, sys
d = '/data/data/com.dearmoon.shield/files'
for i in range(50):
    p = d + '/test_' + str(i) + '.enc'
    f = open(p, 'wb')
    f.write(os.urandom(16384))
    f.flush()
    os.fsync(f.fileno())
    f.close()
sys.stderr.write('done\n')