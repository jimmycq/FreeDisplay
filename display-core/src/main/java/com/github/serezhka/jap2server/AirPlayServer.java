package com.github.serezhka.jap2server;

import com.github.serezhka.jap2lib.AirPlayBonjour;
import com.github.serezhka.jap2server.internal.ControlServer;

import java.net.InetAddress;

public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final AirplayDataConsumer airplayDataConsumer;
    private final ControlServer controlServer;

    private final String serverName;
    private final int airPlayPort;
    private final int airTunesPort;

    public AirPlayServer(String serverName, int airPlayPort, int airTunesPort,
                         AirplayDataConsumer airplayDataConsumer) {
        this(serverName, null, null, airPlayPort, airTunesPort, airplayDataConsumer);
    }

    public AirPlayServer(String serverName, String deviceId, InetAddress bindAddress,
                         int airPlayPort, int airTunesPort,
                         AirplayDataConsumer airplayDataConsumer) {
        this.serverName = serverName;
        this.airPlayBonjour = new AirPlayBonjour(serverName, deviceId, bindAddress);
        this.airPlayPort = airPlayPort;
        this.airTunesPort = airTunesPort;
        this.airplayDataConsumer = airplayDataConsumer;
        this.controlServer = new ControlServer(airPlayPort, airTunesPort, airplayDataConsumer);
    }

    public void start() throws Exception {
        airPlayBonjour.start(airPlayPort, airTunesPort);
        new Thread(controlServer, "airplay-control-server").start();
    }

    public void disconnectActiveSessions() {
        if (controlServer != null) {
            controlServer.stopAllSessions();
            controlServer.disconnectAllClients();
        }
    }

    public void stop() {
        if (airPlayBonjour != null) {
            airPlayBonjour.stop();
        }
        if (controlServer != null) {
            controlServer.stop();
        }
    }
}
