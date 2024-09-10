package pfs.tasks;

import pfs.Constants;
import pfs.FileDirectory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DiscoveryServer {
    private final FileDirectory directory;
    private final String trackerName;
    private final ConcurrentMap<InetAddress, PeerDiscoveryTransceiver> peerDiscoveryTable;
    private ListenerTask discoveryListenerTask;

    public DiscoveryServer(String directory, String trackerName) {
        this.directory = new FileDirectory(directory);
        this.trackerName = trackerName;
        this.peerDiscoveryTable = new ConcurrentHashMap<>();
    }

    public void start() {
        this.discoveryListenerTask = new DiscoveryListener();
        Thread discoveryListenerThread = new Thread(this.discoveryListenerTask);
        discoveryListenerThread.start();
        try {
            this.discoveryListenerTask.waitForReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.connectToDiscoveryNetwork();
    }

    private void addLink(Socket socket) throws IOException {
        this.peerDiscoveryTable.put(socket.getInetAddress(), new PeerDiscoveryTransceiver(socket, null, null));
        String s = "NEIGHBORS:\n";
        for (InetAddress peerAddress : this.peerDiscoveryTable.keySet()) {
            s += peerAddress.getCanonicalHostName() + '\n';
        }
        System.out.print(s);
    }

    private class DiscoveryListener extends ListenerTask {
        public DiscoveryListener() {
            super(Constants.DISCOVERY_PORT);
        }

        @Override
        protected void handleConnection(Socket socket) throws IOException {
            System.out.println("CONNECT FROM: " + socket.getInetAddress().getCanonicalHostName());
            DiscoveryServer.this.addLink(socket);
        }
    }

    private void connectToDiscoveryNetwork() {
        List<InetAddress> peers = this.getAllPeers();

        Random random = new Random();
        if (peers.isEmpty()) {
            return;
        }
        int index = random.nextInt(peers.size());
        InetAddress peerAddress = peers.get(index);
        try {
            Socket discoverySocket = new Socket(peerAddress, Constants.DISCOVERY_PORT);
            System.out.println("CONNECT TO: " + discoverySocket.getInetAddress().getCanonicalHostName());
            this.addLink(discoverySocket);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<InetAddress> getAllPeers() {
        List<InetAddress> peers = new ArrayList<>();
        try (
                Socket trackerSocket = new Socket(this.trackerName, Constants.TRACKER_PORT);
                DataInputStream trackerInput = new DataInputStream(trackerSocket.getInputStream());
                DataOutputStream trackerOutput = new DataOutputStream(trackerSocket.getOutputStream())
        ) {
            trackerOutput.writeByte(0);
            trackerOutput.flush();
            int peersSize = trackerInput.readInt();
            for (int i = 0; i < peersSize; i++) {
                int peerAddressSize = trackerInput.readInt();
                byte[] peerAddress = trackerInput.readNBytes(peerAddressSize);
                peers.add(InetAddress.getByAddress(peerAddress));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return peers;
    }
}
