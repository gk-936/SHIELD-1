package com.dearmoon.shield.detection;

/**
 * Pseudo-Kernel Detection Layer: Syscall Mapper
 * 
 * REUSE STRATEGY:
 * - Maps existing FileObserver events to syscall-like names
 * - No new event types created
 * - Pure mapping layer (no data collection)
 */
public class SyscallMapper {
    
    /**
     * Map FileObserver operation to syscall name
     * REUSES: Existing FileSystemEvent.operation field
     */
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
    
    /**
     * Map network operation to syscall name
     * REUSES: Existing NetworkEvent.protocol field
     */
    public static String networkToSyscall(String protocol) {
        switch (protocol) {
            case "TCP": return "sys_connect";
            case "UDP": return "sys_sendto";
            default: return "sys_socket";
        }
    }
    
    /**
     * Map honeyfile access to syscall name
     * REUSES: Existing HoneyfileEvent.accessType field
     */
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
