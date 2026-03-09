/*
 * bpf_loader.cpp â€” self-contained BPF ELF loader for SHIELD Mode-A
 *
 * No libbpf or libelf dependency.
 * Uses direct bpf() syscalls and ELF64 header parsing via <elf.h>.
 *
 * Supports the legacy BPF ELF format:
 *   SEC("maps")  with  struct bpf_map_def  (no BTF required)
 *   SEC("tracepoint/CATEGORY/EVENT")  for tracepoint programs
 *
 * Kernel requirement: 4.9+ (tested on 4.14 / Android 12).
 *
 * Tracepoint attachment uses:
 *   perf_event_open(PERF_TYPE_TRACEPOINT, id, pid=-1 (all), cpu, ...)
 *   PERF_EVENT_IOC_SET_BPF  +  PERF_EVENT_IOC_ENABLE  per online CPU.
 */

#include "bpf_loader.h"
#include "../include/pid_activity.h"

#include <android/log.h>

#include <elf.h>
#include <linux/bpf.h>
#include <linux/perf_event.h>
#include <asm/unistd.h>       /* __NR_bpf, __NR_perf_event_open */

#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <string>
#include <vector>

#define TAG "SHIELD_BPF_LOADER"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* EM_BPF may not be in older NDK elf.h */
#ifndef EM_BPF
#define EM_BPF 247
#endif

/* BPF_PSEUDO_MAP_FD: src_reg value that marks a map-fd LD_IMM64 insn */
#ifndef BPF_PSEUDO_MAP_FD
#define BPF_PSEUDO_MAP_FD 1
#endif

/* =========================================================================
 * Low-level bpf() / perf_event_open() syscall wrappers
 * ========================================================================= */

static inline int do_bpf(int cmd, union bpf_attr *attr, unsigned int sz)
{
    return (int)syscall(__NR_bpf, cmd, attr, sz);
}

static inline int do_perf_event_open(struct perf_event_attr *attr, pid_t pid,
                                      int cpu, int group, unsigned long flags)
{
    return (int)syscall(__NR_perf_event_open, attr, pid, cpu, group, flags);
}

/* Public map operation wrappers (also used by modea_daemon.cpp) */
int BpfLoader::map_get_next_key(int fd, const void *key, void *next_key)
{
    union bpf_attr a = {};
    a.map_fd   = (__u32)fd;
    a.key      = (__u64)(unsigned long)key;
    a.next_key = (__u64)(unsigned long)next_key;
    return do_bpf(BPF_MAP_GET_NEXT_KEY, &a, sizeof(a));
}

int BpfLoader::map_lookup_elem(int fd, const void *key, void *value)
{
    union bpf_attr a = {};
    a.map_fd = (__u32)fd;
    a.key    = (__u64)(unsigned long)key;
    a.value  = (__u64)(unsigned long)value;
    return do_bpf(BPF_MAP_LOOKUP_ELEM, &a, sizeof(a));
}

int BpfLoader::map_update_elem(int fd, const void *key, const void *value, uint64_t flags)
{
    union bpf_attr a = {};
    a.map_fd = (__u32)fd;
    a.key    = (__u64)(unsigned long)key;
    a.value  = (__u64)(unsigned long)value;
    a.flags  = flags;
    return do_bpf(BPF_MAP_UPDATE_ELEM, &a, sizeof(a));
}

int BpfLoader::map_delete_elem(int fd, const void *key)
{
    union bpf_attr a = {};
    a.map_fd = (__u32)fd;
    a.key    = (__u64)(unsigned long)key;
    return do_bpf(BPF_MAP_DELETE_ELEM, &a, sizeof(a));
}

/* =========================================================================
 * Internal helpers
 * ========================================================================= */

/* Legacy BPF map definition stored in the "maps" ELF section */
struct bpf_map_def {
    unsigned int type;
    unsigned int key_size;
    unsigned int value_size;
    unsigned int max_entries;
    unsigned int map_flags;
};

/* Per-map info accumulated during load */
struct MapEntry {
    std::string  name;
    bpf_map_def  def    = {};
    int          fd     = -1;
    size_t       offset = 0;   /* byte offset within "maps" section */
};

/* Per-program info */
struct ProgEntry {
    std::string section;   /* "tracepoint/android_fs/..." */
    std::string category;  /* "android_fs"                */
    std::string event;     /* "android_fs_datawrite_start" */
    int         fd     = -1;
    int         shidx  = -1;
};

/* Parse "tracepoint/CAT/EVT" â€” returns false if not a tracepoint section */
static bool parse_tp_section(const std::string &sec,
                              std::string &cat, std::string &evt)
{
    static const char pfx[] = "tracepoint/";
    if (sec.compare(0, sizeof(pfx) - 1, pfx) != 0) return false;
    auto rest = sec.substr(sizeof(pfx) - 1);
    auto slash = rest.find('/');
    if (slash == std::string::npos) return false;
    cat = rest.substr(0, slash);
    evt = rest.substr(slash + 1);
    return true;
}

/* Read the integer ID from tracefs for a tracepoint */
static int read_tp_id(const char *cat, const char *evt)
{
    char path[256];
    snprintf(path, sizeof(path),
             "/sys/kernel/tracing/events/%s/%s/id", cat, evt);
    FILE *f = fopen(path, "r");
    if (!f) {
        snprintf(path, sizeof(path),
                 "/sys/kernel/debug/tracing/events/%s/%s/id", cat, evt);
        f = fopen(path, "r");
    }
    if (!f) {
        LOGE("Cannot read tracepoint id for %s/%s: %s", cat, evt, strerror(errno));
        return -1;
    }
    int id = -1;
    fscanf(f, "%d", &id);
    fclose(f);
    return id;
}

/*
 * attach_tp_all_cpus â€” open a perf_event for each online CPU and attach the
 * BPF program.  Returns the number of CPUs successfully attached.
 * All per-CPU perf FDs are appended to out_pfds (must stay open).
 */
static int attach_tp_all_cpus(int prog_fd, const char *cat, const char *evt,
                               std::vector<int> &out_pfds)
{
    int id = read_tp_id(cat, evt);
    if (id < 0) return 0;

    struct perf_event_attr pattr = {};
    pattr.type         = PERF_TYPE_TRACEPOINT;
    pattr.size         = sizeof(pattr);
    pattr.config       = (__u64)id;
    pattr.disabled     = 1;     /* enable after BPF attach */

    int ncpus = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpus < 1) ncpus = 1;
    int attached = 0;

    for (int cpu = 0; cpu < ncpus; cpu++) {
        int pfd = do_perf_event_open(&pattr, -1 /* all pids */, cpu, -1, 0);
        if (pfd < 0) continue;

        if (ioctl(pfd, PERF_EVENT_IOC_SET_BPF, prog_fd) < 0) {
            if (errno == EEXIST) {
                /* kernel 4.14: BPF prog already registered at tracepoint level.
                 * Still call ENABLE so this CPU's perf_event is armed. */
            } else {
                LOGW("PERF_EVENT_IOC_SET_BPF cpu=%d %s/%s: %s",
                     cpu, cat, evt, strerror(errno));
                close(pfd);
                continue;
            }
        }
        if (ioctl(pfd, PERF_EVENT_IOC_ENABLE, 0) < 0) {
            LOGW("PERF_EVENT_IOC_ENABLE cpu=%d %s/%s: %s",
                 cpu, cat, evt, strerror(errno));
            close(pfd);
            continue;
        }
        out_pfds.push_back(pfd);
        attached++;
    }
    if (attached > 0)
        LOGI("Attached %s/%s on %d/%d CPUs (id=%d)", cat, evt, attached, ncpus, id);
    else
        LOGE("Failed to attach %s/%s on any CPU", cat, evt);
    return attached;
}

/* Ensure bpffs is mounted and tracefs symlink exists */
static void ensure_bpf_fs()
{
    struct stat st{};
    if (stat("/sys/fs/bpf", &st) != 0) {
        mkdir("/sys/fs/bpf", 0700);
        if (mount("none", "/sys/fs/bpf", "bpf", 0, nullptr) != 0)
            LOGE("Could not mount bpffs — map pinning will fail");
    }

    /* Create tracefs symlink if missing */
    if (stat("/sys/kernel/debug/tracing/events", &st) != 0 &&
        stat("/sys/kernel/tracing/events", &st) == 0) {
        symlink("/sys/kernel/tracing", "/sys/kernel/debug/tracing");
        LOGI("Tracefs symlinked: /sys/kernel/debug/tracing -> /sys/kernel/tracing");
    }

    if (stat("/sys/kernel/tracing/events/android_fs", &st) == 0 ||
        stat("/sys/kernel/debug/tracing/events/android_fs", &st) == 0)
        LOGI("Tracefs at /sys/kernel/tracing (android_fs present)");
    else
        LOGW("android_fs tracepoints not found");
}

/* =========================================================================
 * BpfLoader constructor / destructor
 * ========================================================================= */

BpfLoader::BpfLoader()  = default;
BpfLoader::~BpfLoader() { unload(); }

/* =========================================================================
 * BpfLoader::load()
 * ========================================================================= */

bool BpfLoader::load(const std::string &path)
{
    ensure_bpf_fs();

    /* ------------------------------------------------------------------
     * 1. Open + mmap the BPF ELF .o file
     * ------------------------------------------------------------------ */
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open(%s): %s", path.c_str(), strerror(errno));
        return false;
    }
    struct stat st{};
    fstat(fd, &st);
    size_t fsz = (size_t)st.st_size;
    if (fsz < sizeof(Elf64_Ehdr)) {
        LOGE("%s: file too small (%zu bytes)", path.c_str(), fsz);
        close(fd);
        return false;
    }

    const char *elf = (const char *)mmap(nullptr, fsz, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (elf == MAP_FAILED) {
        LOGE("mmap(%s): %s", path.c_str(), strerror(errno));
        return false;
    }

    bool ok = false;

    /* ------------------------------------------------------------------
     * 2. Validate ELF64 header
     * ------------------------------------------------------------------ */
    const auto *ehdr = (const Elf64_Ehdr *)elf;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_machine != EM_BPF) {
        LOGE("%s: not a valid BPF ELF64 (machine=0x%x)", path.c_str(), ehdr->e_machine);
        goto out;
    }

    {
    const auto *shdrs   = (const Elf64_Shdr *)(elf + ehdr->e_shoff);
    const auto *shstrtab = elf + shdrs[ehdr->e_shstrndx].sh_offset;

    /* ------------------------------------------------------------------
     * 3. First pass â€” locate key sections
     * ------------------------------------------------------------------ */
    int maps_shidx   = -1;
    int symtab_shidx = -1;
    int strtab_shidx = -1;
    const char *license = "GPL";

    for (int i = 0; i < (int)ehdr->e_shnum; i++) {
        const char *sn = shstrtab + shdrs[i].sh_name;
        if (strcmp(sn, "maps") == 0)                maps_shidx   = i;
        else if (shdrs[i].sh_type == SHT_SYMTAB) {  symtab_shidx = i;
                                                     strtab_shidx = (int)shdrs[i].sh_link; }
        else if (strcmp(sn, "license") == 0 && shdrs[i].sh_size > 0)
            license = elf + shdrs[i].sh_offset;
    }

    if (maps_shidx < 0)   { LOGE("No 'maps' section in BPF ELF");    goto out; }
    if (symtab_shidx < 0) { LOGE("No symbol table in BPF ELF");      goto out; }

    const auto &maps_shdr = shdrs[maps_shidx];
    const char *maps_data  = elf + maps_shdr.sh_offset;
    size_t      num_maps   = maps_shdr.sh_size / sizeof(bpf_map_def);

    const auto *syms    = (const Elf64_Sym *)(elf + shdrs[symtab_shidx].sh_offset);
    int         num_syms = (int)(shdrs[symtab_shidx].sh_size / sizeof(Elf64_Sym));
    const char *strtab  = elf + shdrs[strtab_shidx].sh_offset;

    /* ------------------------------------------------------------------
     * 4. Create BPF maps
     * ------------------------------------------------------------------ */
    std::vector<MapEntry> maps(num_maps);
    for (size_t i = 0; i < num_maps; i++) {
        const auto *def = (const bpf_map_def *)(maps_data + i * sizeof(bpf_map_def));
        maps[i].def    = *def;
        maps[i].offset = i * sizeof(bpf_map_def);

        union bpf_attr a = {};
        a.map_type   = def->type;
        a.key_size   = def->key_size;
        a.value_size = def->value_size;
        a.max_entries = def->max_entries;
        a.map_flags  = def->map_flags;
        maps[i].fd = do_bpf(BPF_MAP_CREATE, &a, sizeof(a));
        if (maps[i].fd < 0)
            LOGE("BPF_MAP_CREATE map[%zu] type=%u: %s", i, def->type, strerror(errno));
        else
            LOGI("Created BPF map[%zu] type=%u fd=%d", i, def->type, maps[i].fd);
    }

    /* Assign names via symbol table */
    for (int i = 0; i < num_syms; i++) {
        const auto &sym = syms[i];
        if ((int)sym.st_shndx != maps_shidx) continue;
        if (ELF64_ST_TYPE(sym.st_info) != STT_OBJECT) continue;
        size_t mi = sym.st_value / sizeof(bpf_map_def);
        if (mi < num_maps) {
            maps[mi].name = strtab + sym.st_name;
            LOGI("Map[%zu] name='%s'", mi, maps[mi].name.c_str());
        }
    }

    /* Cache well-known map FDs */
    for (const auto &m : maps) {
        if (m.name == "shield_pid_activity") pid_activity_fd_  = m.fd;
        if (m.name == "shield_suspect_pids") suspect_pids_fd_  = m.fd;
    }

    /* ------------------------------------------------------------------
     * 5. Collect program sections and load them (with relocations)
     * ------------------------------------------------------------------ */
    std::vector<ProgEntry> progs;
    for (int i = 0; i < (int)ehdr->e_shnum; i++) {
        if (shdrs[i].sh_type != SHT_PROGBITS) continue;
        const char *sn = shstrtab + shdrs[i].sh_name;
        ProgEntry pe;
        pe.shidx   = i;
        pe.section = sn;
        if (!parse_tp_section(pe.section, pe.category, pe.event)) continue;
        progs.push_back(pe);
    }

    for (auto &prog : progs) {
        const auto &pshdr  = shdrs[prog.shidx];
        size_t      insn_cnt = pshdr.sh_size / sizeof(bpf_insn);

        /* Copy instructions so we can patch them */
        std::vector<bpf_insn> insns(insn_cnt);
        memcpy(insns.data(), elf + pshdr.sh_offset, pshdr.sh_size);

        /* Find relocation section (.rel or .rela prefix + section name) */
        std::string rel_name  = std::string(".rel")  + prog.section;
        std::string rela_name = std::string(".rela") + prog.section;

        for (int ri = 0; ri < (int)ehdr->e_shnum; ri++) {
            const char *sn = shstrtab + shdrs[ri].sh_name;
            bool is_rela = (shdrs[ri].sh_type == SHT_RELA && rela_name == sn);
            bool is_rel  = (shdrs[ri].sh_type == SHT_REL  && rel_name  == sn);
            if (!is_rel && !is_rela) continue;

            size_t ent_sz = is_rela ? sizeof(Elf64_Rela) : sizeof(Elf64_Rel);
            size_t count  = shdrs[ri].sh_size / ent_sz;
            const char *rel_data = elf + shdrs[ri].sh_offset;

            for (size_t j = 0; j < count; j++) {
                uint64_t r_off;
                uint32_t sym_idx;
                if (is_rela) {
                    auto *r = (const Elf64_Rela *)(rel_data + j * ent_sz);
                    r_off   = r->r_offset;
                    sym_idx = (uint32_t)ELF64_R_SYM(r->r_info);
                } else {
                    auto *r = (const Elf64_Rel *)(rel_data + j * ent_sz);
                    r_off   = r->r_offset;
                    sym_idx = (uint32_t)ELF64_R_SYM(r->r_info);
                }

                if (sym_idx >= (uint32_t)num_syms) continue;
                const auto &sym = syms[sym_idx];
                if ((int)sym.st_shndx != maps_shidx) continue;

                size_t mi = sym.st_value / sizeof(bpf_map_def);
                if (mi >= num_maps || maps[mi].fd < 0) continue;

                size_t insn_off = r_off / sizeof(bpf_insn);
                if (insn_off >= insn_cnt) continue;

                /* Patch BPF_LD_IMM64: set src_reg=PSEUDO_MAP_FD, imm=fd */
                insns[insn_off].src_reg = BPF_PSEUDO_MAP_FD;
                insns[insn_off].imm     = maps[mi].fd;
                if (insn_off + 1 < insn_cnt)
                    insns[insn_off + 1].imm = 0;
            }
            break; /* found the reloc section */
        }

        /* Load the program */
        char log_buf[65536] = {};
        union bpf_attr pa = {};
        pa.prog_type = BPF_PROG_TYPE_TRACEPOINT;
        pa.insn_cnt  = (uint32_t)insn_cnt;
        pa.insns     = (__u64)(unsigned long)insns.data();
        pa.license   = (__u64)(unsigned long)license;
        pa.log_level = 1;
        pa.log_size  = sizeof(log_buf);
        pa.log_buf   = (__u64)(unsigned long)log_buf;

        prog.fd = do_bpf(BPF_PROG_LOAD, &pa, sizeof(pa));
        if (prog.fd < 0) {
            LOGE("BPF_PROG_LOAD %s: %s", prog.section.c_str(), strerror(errno));
            if (log_buf[0]) LOGE("Verifier: %.4000s", log_buf);
        } else {
            LOGI("Loaded BPF prog: %s fd=%d", prog.section.c_str(), prog.fd);
            prog_fds_.push_back(prog.fd);
        }
    }

    /* ------------------------------------------------------------------
     * 6. Pin maps at /sys/fs/bpf/
     * ------------------------------------------------------------------ */
    static const struct { const char *name; const char *pin; } PIN_TABLE[] = {
        { "shield_pid_activity", "/sys/fs/bpf/shield_pid_activity" },
        { "shield_suspect_pids", "/sys/fs/bpf/shield_suspect_pids" },
    };
    for (const auto &m : maps) {
        for (const auto &pt : PIN_TABLE) {
            if (m.name != pt.name || m.fd < 0) continue;
            unlink(pt.pin);  /* remove stale pin */
            union bpf_attr pa = {};
            pa.pathname = (__u64)(unsigned long)pt.pin;
            pa.bpf_fd   = (__u32)m.fd;
            if (do_bpf(BPF_OBJ_PIN, &pa, sizeof(pa)) < 0)
                LOGW("BPF_OBJ_PIN '%s': %s", pt.pin, strerror(errno));
            else
                LOGI("Pinned map '%s' at %s", m.name.c_str(), pt.pin);
        }
    }

    /* ------------------------------------------------------------------
     * 7. Attach tracepoints
     * ------------------------------------------------------------------ */
    int attached = 0;
    for (const auto &prog : progs) {
        if (prog.fd < 0) continue;
        int n = attach_tp_all_cpus(prog.fd, prog.category.c_str(),
                                   prog.event.c_str(), perf_fds_);
        attached += n;
    }

    if (attached == 0) {
        LOGE("No tracepoints could be attached â€” check kernel BPF config");
        goto out;
    }
    LOGI("Mode-A BPF loaded: %d tracepoint attachments across %zu programs",
         attached, progs.size());
    ok = true;

    } /* end scope */

out:
    munmap((void *)elf, fsz);
    if (!ok) unload();
    loaded_ = ok;
    return ok;
}

/* =========================================================================
 * BpfLoader::unload()
 * ========================================================================= */

void BpfLoader::unload()
{
    for (int pfd : perf_fds_) {
        ioctl(pfd, PERF_EVENT_IOC_DISABLE, 0);
        close(pfd);
    }
    perf_fds_.clear();

    for (int fd : prog_fds_) close(fd);
    prog_fds_.clear();

    if (pid_activity_fd_ >= 0) { close(pid_activity_fd_); pid_activity_fd_ = -1; }
    if (suspect_pids_fd_ >= 0) { close(suspect_pids_fd_); suspect_pids_fd_ = -1; }

    unlink("/sys/fs/bpf/shield_pid_activity");
    unlink("/sys/fs/bpf/shield_suspect_pids");

    loaded_ = false;
}

/* =========================================================================
 * BpfLoader::mark_suspect_pid() / clear_suspect_pid()
 * ========================================================================= */

void BpfLoader::mark_suspect_pid(uint32_t pid)
{
    if (suspect_pids_fd_ < 0) return;
    uint32_t val = 1;
    map_update_elem(suspect_pids_fd_, &pid, &val, BPF_ANY);
}

void BpfLoader::clear_suspect_pid(uint32_t pid)
{
    if (suspect_pids_fd_ < 0) return;
    map_delete_elem(suspect_pids_fd_, &pid);
}

