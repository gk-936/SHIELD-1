package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public class NetworkEvent extends TelemetryEvent {
    private String destIp;
    private int destPort;
    private String protocol;
    private int sentBytes;
    private int receivedBytes;
    private int uid;

    public NetworkEvent(String destIp, int destPort, String protocol, int sentBytes, int receivedBytes, int uid) {
        super("NETWORK");
        this.destIp = destIp;
        this.destPort = destPort;
        this.protocol = protocol;
        this.sentBytes = sentBytes;
        this.receivedBytes = receivedBytes;
        this.uid = uid;
    }

    @Override
    public JSONObject toJSON() {
        try {
            JSONObject json = getBaseJSON();
            json.put("destinationIp", destIp);
            json.put("destinationPort", destPort);
            json.put("protocol", protocol);
            json.put("bytesSent", sentBytes);
            json.put("bytesReceived", receivedBytes);
            json.put("appUid", uid);
            return json;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public String getDestIp() {
        return destIp;
    }

    public int getDestPort() {
        return destPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public int getSentBytes() {
        return sentBytes;
    }

    public int getReceivedBytes() {
        return receivedBytes;
    }

    public int getUid() {
        return uid;
    }
}
