package com.github.serezhka.jap2lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Registers airplay/airtunes service mdns
 */
public class AirPlayBonjour {

    private static final Logger log = LoggerFactory.getLogger(AirPlayBonjour.class);

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";
    private static final String AIRTUNES_SERVICE_TYPE = "_raop._tcp.local.";

    private final String serverName;
    private final String deviceId;
    private final InetAddress bindAddress;
    private final String pairingId;

    private JmDNS jmDNS;
    private ServiceInfo airPlayService;
    private ServiceInfo airTunesService;

    public AirPlayBonjour(String serverName) {
        this(serverName, "01:02:03:04:05:06", null);
    }

    public AirPlayBonjour(String serverName, String deviceId, InetAddress bindAddress) {
        this.serverName = serverName;
        this.deviceId = (deviceId != null && !deviceId.trim().isEmpty()) ? deviceId.trim() : "01:02:03:04:05:06";
        this.bindAddress = bindAddress;
        this.pairingId = UUID.nameUUIDFromBytes(this.deviceId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public synchronized void start(int airPlayPort, int airTunesPort) throws Exception {
        stop(); // Ensure any previous instance is cleaned up

        if (bindAddress != null) {
            log.info("Starting JmDNS bound specifically to IPv4 {}", bindAddress.getHostAddress());
            jmDNS = JmDNS.create(bindAddress);
        } else {
            log.info("Starting JmDNS on default interface");
            jmDNS = JmDNS.create();
        }

        // In AirPlay protocol, airTunesPort (7000) is the RTSP control server port.
        // Both _airplay and _raop should advertise port 7000 for Apple clients to connect to RTSP.
        airPlayService = ServiceInfo.create(AIRPLAY_SERVICE_TYPE,
                serverName, airTunesPort, 0, 0, airPlayMDNSProps());
        jmDNS.registerService(airPlayService);
        log.info("{} service [{}] is registered on port {}", AIRPLAY_SERVICE_TYPE, serverName, airTunesPort);

        String macCompact = deviceId.replace(":", "").toUpperCase();
        String airTunesServerName = macCompact + "@" + serverName;
        airTunesService = ServiceInfo.create(AIRTUNES_SERVICE_TYPE,
                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps());
        jmDNS.registerService(airTunesService);
        log.info("{} service [{}] is registered on port {}", AIRTUNES_SERVICE_TYPE, airTunesServerName, airTunesPort);
    }

    public synchronized void stop() {
        if (jmDNS != null) {
            try {
                if (airPlayService != null) {
                    jmDNS.unregisterService(airPlayService);
                }
                if (airTunesService != null) {
                    jmDNS.unregisterService(airTunesService);
                }
                jmDNS.unregisterAllServices();
                jmDNS.close();
                log.info("JmDNS stopped and unregistered all AirPlay services");
            } catch (Exception e) {
                log.warn("Error stopping JmDNS", e);
            } finally {
                jmDNS = null;
                airPlayService = null;
                airTunesService = null;
            }
        }
    }

    private Map<String, String> airPlayMDNSProps() {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", "0x5A7FFFF7,0x1E");
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x4");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV2,1");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        airPlayMDNSProps.put("pi", pairingId);
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "0,1,2,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", "0x5A7FFFF7,0x1E");
        airTunesMDNSProps.put("am", "AppleTV2,1");
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("rhd", "5.6.0.0");
        airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x4");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        airTunesMDNSProps.put("deviceid", deviceId);
        return airTunesMDNSProps;
    }
}
