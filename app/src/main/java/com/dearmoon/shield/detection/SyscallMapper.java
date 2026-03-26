package com.dearmoon.shield.detection;

// Syscall mapper layer
public class SyscallMapper {
    
    // Map to syscall
    // Reuses existing operation
    public static String toSyscall(String fileObserverOp) {
        switch (fileObserverOp) {
            case "CREATE": return "sys_creat";
            case "OPEN": return "sys_open";
            case "MODIFY": return "sys_write";
            case "CLOSE_WRITE": return "sys_close";
            case "DELETE": return "sys_unlink";
            case "MOVED_TO": return "sys_rename";
            default: return "sys_unknown";
        }
    }
    
    // Map network syscall
    // Reuses existing protocol
    public static String networkToSyscall(String protocol) {
        switch (protocol) {
            case "TCP": return "sys_connect";
            case "UDP": return "sys_sendto";
            default: return "sys_socket";
        }
    }
    
    // Map honeyfile syscall
    // Reuses existing accessType
    public static String honeyfileToSyscall(String accessType) {
        switch (accessType) {
            case "OPEN": return "sys_open";
            case "MODIFY": return "sys_write";
            case "DELETE": return "sys_unlink";
            case "WRITE": return "sys_write";
            default: return "sys_access";
        }
    }
}
