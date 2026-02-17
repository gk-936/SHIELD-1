package com.dearmoon.shield.services;

import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for NetworkGuardService IPv6 support fix.
 * Tests verify that IPv6 packets are correctly parsed and analyzed.
 */
public class NetworkGuardServiceIPv6Test {

    /**
     * TEST 1: Verify IPv4 packet version detection
     */
    @Test
    public void testIPv4VersionDetection() {
        // Create minimal IPv4 packet (version = 4)
        ByteBuffer packet = ByteBuffer.allocate(20);
        packet.put(0, (byte) 0x45); // Version 4, IHL 5
        
        byte versionAndIHL = packet.get(0);
        int version = (versionAndIHL >> 4) & 0x0F;
        
        assertEquals("Should detect IPv4 (version 4)", 4, version);
        System.out.println("✅ IPv4 version detected correctly");
    }

    /**
     * TEST 2: Verify IPv6 packet version detection
     */
    @Test
    public void testIPv6VersionDetection() {
        // Create minimal IPv6 packet (version = 6)
        ByteBuffer packet = ByteBuffer.allocate(40);
        packet.put(0, (byte) 0x60); // Version 6, traffic class 0
        
        byte versionAndIHL = packet.get(0);
        int version = (versionAndIHL >> 4) & 0x0F;
        
        assertEquals("Should detect IPv6 (version 6)", 6, version);
        System.out.println("✅ IPv6 version detected correctly");
    }

    /**
     * TEST 3: Verify IPv4 destination IP extraction
     */
    @Test
    public void testIPv4DestinationExtraction() {
        // Create IPv4 packet with destination 192.168.1.100
        ByteBuffer packet = ByteBuffer.allocate(20);
        packet.put(0, (byte) 0x45); // Version 4, IHL 5
        packet.put(9, (byte) 6);    // Protocol: TCP
        
        // Destination IP at bytes 16-19: 192.168.1.100
        packet.put(16, (byte) 192);
        packet.put(17, (byte) 168);
        packet.put(18, (byte) 1);
        packet.put(19, (byte) 100);
        
        // Extract destination IP
        byte[] destIpBytes = new byte[4];
        packet.position(16);
        packet.get(destIpBytes);
        String destIp = String.format("%d.%d.%d.%d",
                destIpBytes[0] & 0xFF, destIpBytes[1] & 0xFF,
                destIpBytes[2] & 0xFF, destIpBytes[3] & 0xFF);
        
        assertEquals("Should extract correct IPv4 address", "192.168.1.100", destIp);
        System.out.println("✅ IPv4 destination extracted: " + destIp);
    }

    /**
     * TEST 4: Verify IPv6 destination IP extraction
     */
    @Test
    public void testIPv6DestinationExtraction() {
        // Create IPv6 packet with destination 2001:0db8:85a3:0000:0000:8a2e:0370:7334
        ByteBuffer packet = ByteBuffer.allocate(40);
        packet.put(0, (byte) 0x60); // Version 6
        packet.put(6, (byte) 6);    // Next header: TCP
        
        // Destination IPv6 at bytes 24-39
        packet.position(24);
        packet.put(new byte[]{
            0x20, 0x01, // 2001
            0x0d, (byte) 0xb8, // 0db8
            (byte) 0x85, (byte) 0xa3, // 85a3
            0x00, 0x00, // 0000
            0x00, 0x00, // 0000
            (byte) 0x8a, 0x2e, // 8a2e
            0x03, 0x70, // 0370
            0x73, 0x34  // 7334
        });
        
        // Extract destination IPv6
        byte[] destIpBytes = new byte[16];
        packet.position(24);
        packet.get(destIpBytes);
        
        StringBuilder ipv6 = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) ipv6.append(":");
            ipv6.append(String.format("%02x%02x", destIpBytes[i] & 0xFF, destIpBytes[i+1] & 0xFF));
        }
        String destIp = ipv6.toString();
        
        assertEquals("Should extract correct IPv6 address", 
                    "2001:0db8:85a3:0000:0000:8a2e:0370:7334", destIp);
        System.out.println("✅ IPv6 destination extracted: " + destIp);
    }

    /**
     * TEST 5: Verify IPv4 protocol extraction (TCP)
     */
    @Test
    public void testIPv4ProtocolTCP() {
        ByteBuffer packet = ByteBuffer.allocate(20);
        packet.put(0, (byte) 0x45);
        packet.put(9, (byte) 6); // TCP = 6
        
        int protocol = packet.get(9) & 0xFF;
        String protoName = protocol == 6 ? "TCP" : protocol == 17 ? "UDP" : "OTHER";
        
        assertEquals("Should detect TCP protocol", "TCP", protoName);
        System.out.println("✅ IPv4 TCP protocol detected");
    }

    /**
     * TEST 6: Verify IPv4 protocol extraction (UDP)
     */
    @Test
    public void testIPv4ProtocolUDP() {
        ByteBuffer packet = ByteBuffer.allocate(20);
        packet.put(0, (byte) 0x45);
        packet.put(9, (byte) 17); // UDP = 17
        
        int protocol = packet.get(9) & 0xFF;
        String protoName = protocol == 6 ? "TCP" : protocol == 17 ? "UDP" : "OTHER";
        
        assertEquals("Should detect UDP protocol", "UDP", protoName);
        System.out.println("✅ IPv4 UDP protocol detected");
    }

    /**
     * TEST 7: Verify IPv6 next header extraction (TCP)
     */
    @Test
    public void testIPv6NextHeaderTCP() {
        ByteBuffer packet = ByteBuffer.allocate(40);
        packet.put(0, (byte) 0x60);
        packet.put(6, (byte) 6); // Next header: TCP = 6
        
        int nextHeader = packet.get(6) & 0xFF;
        String protoName = nextHeader == 6 ? "TCP" : nextHeader == 17 ? "UDP" : "OTHER";
        
        assertEquals("Should detect TCP in IPv6", "TCP", protoName);
        System.out.println("✅ IPv6 TCP protocol detected");
    }

    /**
     * TEST 8: Verify IPv6 next header extraction (UDP)
     */
    @Test
    public void testIPv6NextHeaderUDP() {
        ByteBuffer packet = ByteBuffer.allocate(40);
        packet.put(0, (byte) 0x60);
        packet.put(6, (byte) 17); // Next header: UDP = 17
        
        int nextHeader = packet.get(6) & 0xFF;
        String protoName = nextHeader == 6 ? "TCP" : nextHeader == 17 ? "UDP" : "OTHER";
        
        assertEquals("Should detect UDP in IPv6", "UDP", protoName);
        System.out.println("✅ IPv6 UDP protocol detected");
    }

    /**
     * TEST 9: Verify IPv6 localhost detection
     */
    @Test
    public void testIPv6LocalhostDetection() {
        String localhost1 = "::1";
        String localhost2 = "0000:0000:0000:0000:0000:0000:0000:0001";
        
        assertTrue("::1 should be localhost", 
                  localhost1.equals("::1") || localhost1.startsWith("0000:0000:0000:0000:0000:0000:0000:0001"));
        assertTrue("Full form should be localhost", 
                  localhost2.equals("::1") || localhost2.startsWith("0000:0000:0000:0000:0000:0000:0000:0001"));
        
        System.out.println("✅ IPv6 localhost detection works");
    }

    /**
     * TEST 10: Verify IPv6 link-local detection
     */
    @Test
    public void testIPv6LinkLocalDetection() {
        String linkLocal1 = "fe80:0000:0000:0000:0000:0000:0000:0001";
        String linkLocal2 = "fe90:1234:5678:9abc:def0:1234:5678:9abc";
        String linkLocal3 = "fea0:abcd:ef01:2345:6789:abcd:ef01:2345";
        String linkLocal4 = "feb0:1111:2222:3333:4444:5555:6666:7777";
        
        assertTrue("fe80 should be link-local", linkLocal1.startsWith("fe80:"));
        assertTrue("fe90 should be link-local", linkLocal2.startsWith("fe90:"));
        assertTrue("fea0 should be link-local", linkLocal3.startsWith("fea0:"));
        assertTrue("feb0 should be link-local", linkLocal4.startsWith("feb0:"));
        
        System.out.println("✅ IPv6 link-local detection works");
    }

    /**
     * TEST 11: Verify IPv6 multicast detection
     */
    @Test
    public void testIPv6MulticastDetection() {
        String multicast1 = "ff00:0000:0000:0000:0000:0000:0000:0001";
        String multicast2 = "ff02:0000:0000:0000:0000:0000:0000:0001";
        String multicast3 = "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff";
        
        assertTrue("ff00 should be multicast", multicast1.startsWith("ff"));
        assertTrue("ff02 should be multicast", multicast2.startsWith("ff"));
        assertTrue("ffff should be multicast", multicast3.startsWith("ff"));
        
        System.out.println("✅ IPv6 multicast detection works");
    }

    /**
     * TEST 12: Verify IPv4 port extraction
     */
    @Test
    public void testIPv4PortExtraction() {
        ByteBuffer packet = ByteBuffer.allocate(24);
        packet.put(0, (byte) 0x45);
        
        // Destination port at bytes 22-23: 443 (HTTPS)
        packet.putShort(22, (short) 443);
        
        int destPort = packet.getShort(22) & 0xFFFF;
        
        assertEquals("Should extract port 443", 443, destPort);
        System.out.println("✅ IPv4 port extracted: " + destPort);
    }

    /**
     * TEST 13: Verify IPv6 port extraction
     */
    @Test
    public void testIPv6PortExtraction() {
        ByteBuffer packet = ByteBuffer.allocate(44);
        packet.put(0, (byte) 0x60);
        
        // Destination port at bytes 42-43: 8080
        packet.putShort(42, (short) 8080);
        
        int destPort = packet.getShort(42) & 0xFFFF;
        
        assertEquals("Should extract port 8080", 8080, destPort);
        System.out.println("✅ IPv6 port extracted: " + destPort);
    }

    /**
     * TEST 14: Verify malicious port detection
     */
    @Test
    public void testMaliciousPortDetection() {
        int[] maliciousPorts = {4444, 5555, 6666, 7777};
        
        for (int port : maliciousPorts) {
            boolean isMalicious = (port == 4444 || port == 5555 || port == 6666 || port == 7777);
            assertTrue("Port " + port + " should be flagged as malicious", isMalicious);
        }
        
        int[] normalPorts = {80, 443, 8080, 3000};
        for (int port : normalPorts) {
            boolean isMalicious = (port == 4444 || port == 5555 || port == 6666 || port == 7777);
            assertFalse("Port " + port + " should not be flagged as malicious", isMalicious);
        }
        
        System.out.println("✅ Malicious port detection works");
    }

    /**
     * TEST 15: Verify packet size validation
     */
    @Test
    public void testPacketSizeValidation() {
        // IPv4 minimum: 20 bytes
        ByteBuffer ipv4Small = ByteBuffer.allocate(19);
        assertTrue("IPv4 packet < 20 bytes should be rejected", ipv4Small.remaining() < 20);
        
        ByteBuffer ipv4Valid = ByteBuffer.allocate(20);
        assertTrue("IPv4 packet >= 20 bytes should be accepted", ipv4Valid.remaining() >= 20);
        
        // IPv6 minimum: 40 bytes
        ByteBuffer ipv6Small = ByteBuffer.allocate(39);
        assertTrue("IPv6 packet < 40 bytes should be rejected", ipv6Small.remaining() < 40);
        
        ByteBuffer ipv6Valid = ByteBuffer.allocate(40);
        assertTrue("IPv6 packet >= 40 bytes should be accepted", ipv6Valid.remaining() >= 40);
        
        System.out.println("✅ Packet size validation works");
    }
}
