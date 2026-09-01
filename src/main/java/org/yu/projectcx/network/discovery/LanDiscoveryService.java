package org.yu.projectcx.network.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.db.PeerRepository;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.util.AsyncExecutor;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic Local Network (LAN) Peer Discovery Service.
 * 
 * Uses Dual-Path Discovery:
 * 1. UDP Multicast (239.255.0.1:9888) and Broadcast (255.255.255.255:9888) across the local subnet.
 * 2. Shared SQLite Peer Heartbeats for instantaneous multi-instance detection on the same host.
 */
public class LanDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(LanDiscoveryService.class);
    public static final int DISCOVERY_PORT = 9888;
    public static final String MULTICAST_GROUP_IP = "239.255.0.1";
    public static final String MAGIC_HEADER = "PROJECT_CX_PEER";

    private final String localPeerId;
    private final String localAlias;
    private final int localSignalingPort;
    private final List<PeerDiscoveryListener> listeners;
    private final AtomicBoolean isRunning;
    private PeerRepository peerRepository;

    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private ScheduledExecutorService broadcastScheduler;
    private Thread listenerThread;

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(Peer peer);
        default void onPeerOffline(String peerId) {}
    }

    public LanDiscoveryService(String localPeerId, String localAlias, int localSignalingPort) {
        this(localPeerId, localAlias, localSignalingPort, null);
    }

    public LanDiscoveryService(String localPeerId, String localAlias, int localSignalingPort, PeerRepository peerRepository) {
        this.localPeerId = localPeerId;
        this.localAlias = localAlias;
        this.localSignalingPort = localSignalingPort;
        this.peerRepository = peerRepository;
        this.listeners = new CopyOnWriteArrayList<>();
        this.isRunning = new AtomicBoolean(false);
    }

    public void setPeerRepository(PeerRepository peerRepository) {
        this.peerRepository = peerRepository;
    }

    public synchronized void start() {
        if (isRunning.get()) return;

        try {
            this.multicastGroup = InetAddress.getByName(MULTICAST_GROUP_IP);
            try {
                this.multicastSocket = new MulticastSocket(DISCOVERY_PORT);
                this.multicastSocket.setReuseAddress(true);
                this.multicastSocket.setLoopbackMode(false); // Enable loopback for local testing

                // Join the multicast group on all active network interfaces
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isUp() && ni.supportsMulticast()) {
                        try {
                            this.multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, DISCOVERY_PORT), ni);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception se) {
                logger.debug("MulticastSocket bind note: {}", se.getMessage());
            }

            isRunning.set(true);

            // 1. Start Listener Thread
            if (multicastSocket != null) {
                listenerThread = new Thread(this::listenLoop, "LAN-Discovery-Listener");
                listenerThread.setDaemon(true);
                listenerThread.start();
            }

            // 2. Start Periodic Presence Beacon (every 1 second)
            broadcastScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LAN-Broadcast-Beacon");
                t.setDaemon(true);
                return t;
            });
            broadcastScheduler.scheduleAtFixedRate(this::broadcastPresence, 0, 1, TimeUnit.SECONDS);

            logger.info("LAN Peer Auto-Discovery Service started (Local: [{}] on port {})", localAlias, localSignalingPort);
        } catch (Exception e) {
            logger.warn("Could not start LAN Multicast Auto-Discovery service: {}", e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!isRunning.compareAndSet(true, false)) return;

        if (peerRepository != null) {
            peerRepository.markPeerOffline(localPeerId);
        }

        if (broadcastScheduler != null && !broadcastScheduler.isShutdown()) {
            broadcastScheduler.shutdownNow();
        }

        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                if (multicastGroup != null) {
                    multicastSocket.leaveGroup(new InetSocketAddress(multicastGroup, DISCOVERY_PORT), null);
                }
            } catch (Exception ignored) {}
            multicastSocket.close();
        }
        logger.info("LAN Auto-Discovery service stopped.");
    }

    /**
     * Broadcasts node presence packet over Shared DB Heartbeat, Multicast group, and LAN broadcast.
     */
    public void broadcastPresence() {
        if (!isRunning.get()) return;

        // Path 1: Database Heartbeat sync (for instant reliable discovery on same host)
        if (peerRepository != null) {
            peerRepository.saveHeartbeat(localPeerId, localAlias, "127.0.0.1", localSignalingPort);
            List<Peer> livePeers = peerRepository.getActiveLivePeers(4);
            java.util.Set<String> activeIds = new java.util.HashSet<>();
            for (Peer p : livePeers) {
                if (!p.getPeerId().equalsIgnoreCase(localPeerId) && !p.getAlias().equalsIgnoreCase(localAlias)) {
                    activeIds.add(p.getPeerId().toLowerCase());
                    activeIds.add(p.getAlias().toLowerCase());
                    notifyPeerDiscovered(p);
                }
            }
            // Check all known peers to detect offline state
            List<Peer> allPeers = peerRepository.getAllPeers();
            for (Peer p : allPeers) {
                if (!p.getPeerId().equalsIgnoreCase(localPeerId) && !p.getAlias().equalsIgnoreCase(localAlias)) {
                    if (!activeIds.contains(p.getPeerId().toLowerCase()) && !activeIds.contains(p.getAlias().toLowerCase())) {
                        notifyPeerOffline(p.getPeerId());
                    }
                }
            }
        }

        // Path 2: Network UDP Multicast & Broadcast (for LAN subnet nodes)
        if (multicastSocket == null || multicastSocket.isClosed()) return;

        String packetData = String.format("%s||%s||%s||%d||%d",
                MAGIC_HEADER,
                localPeerId,
                localAlias,
                localSignalingPort,
                System.currentTimeMillis()
        );

        byte[] buffer = packetData.getBytes(StandardCharsets.UTF_8);

        try {
            // 1. Send to Multicast Group
            if (multicastGroup != null) {
                DatagramPacket mPacket = new DatagramPacket(buffer, buffer.length, multicastGroup, DISCOVERY_PORT);
                multicastSocket.send(mPacket);
            }

            // 2. Send direct loopback packet
            DatagramPacket loopbackPacket = new DatagramPacket(buffer, buffer.length, InetAddress.getByName("127.0.0.1"), DISCOVERY_PORT);
            multicastSocket.send(loopbackPacket);

            // 3. Send to Broadcast Addresses
            List<InetAddress> broadcastAddresses = listAllBroadcastAddresses();
            for (InetAddress address : broadcastAddresses) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, DISCOVERY_PORT);
                    multicastSocket.send(packet);
                } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            logger.debug("Failed sending LAN discovery packet: {}", e.getMessage());
        }
    }

    /**
     * Probes local network and triggers an instant broadcast and DB sync.
     */
    public void triggerLanScanAsync() {
        AsyncExecutor.runAsyncNetwork(() -> {
            logger.info("Initiating active LAN peer sweep...");
            for (int i = 0; i < 3; i++) {
                broadcastPresence();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}
            }
        });
    }

    private void listenLoop() {
        byte[] buffer = new byte[1024];

        while (isRunning.get() && multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                parseAndHandlePacket(raw, packet.getAddress());
            } catch (SocketException se) {
                if (!isRunning.get()) break;
            } catch (IOException e) {
                if (isRunning.get()) {
                    logger.debug("LAN Discovery packet receive error: {}", e.getMessage());
                }
            }
        }
    }

    private void parseAndHandlePacket(String raw, InetAddress senderAddress) {
        if (raw == null || !raw.startsWith(MAGIC_HEADER)) return;

        String[] parts = raw.split("\\|\\|");
        if (parts.length >= 4) {
            String remotePeerId = parts[1].trim();
            String remoteAlias = parts[2].trim();
            int remoteSignalingPort;

            try {
                remoteSignalingPort = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException nfe) {
                return;
            }

            // Ignore self-announcements
            if (remotePeerId.equalsIgnoreCase(localPeerId) || remoteSignalingPort == localSignalingPort || remoteAlias.equalsIgnoreCase(localAlias)) {
                return;
            }

            String remoteIp = senderAddress.getHostAddress();
            if (remoteIp == null || remoteIp.startsWith("127.") || "0:0:0:0:0:0:0:1".equals(remoteIp)) {
                remoteIp = "127.0.0.1";
            }

            Peer discoveredPeer = new Peer(remotePeerId, remoteAlias, remoteIp, remoteSignalingPort);
            discoveredPeer.setOnline(true);

            logger.info("📡 Auto-Discovered LAN Peer: {} ({}:{})", remoteAlias, remoteIp, remoteSignalingPort);
            notifyPeerDiscovered(discoveredPeer);
        }
    }

    private List<InetAddress> listAllBroadcastAddresses() throws SocketException {
        List<InetAddress> broadcastList = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();

            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }

            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress broadcast = interfaceAddress.getBroadcast();
                if (broadcast != null) {
                    broadcastList.add(broadcast);
                }
            }
        }

        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {}

        return broadcastList;
    }

    public void addListener(PeerDiscoveryListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PeerDiscoveryListener listener) {
        listeners.remove(listener);
    }

    private void notifyPeerDiscovered(Peer peer) {
        for (PeerDiscoveryListener listener : listeners) {
            listener.onPeerDiscovered(peer);
        }
    }

    private void notifyPeerOffline(String peerId) {
        for (PeerDiscoveryListener listener : listeners) {
            listener.onPeerOffline(peerId);
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}
