package pfs.tasks;

import pfs.Constants;
import pfs.FileDirectory;
import pfs.messages.DiscoveryMessage;
import pfs.messages.DiscoveryQueryMessage;
import pfs.messages.DiscoveryReplyMessage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.*;

public class DiscoveryServer implements Runnable {
    private final FileDirectory directory;
    private final String trackerName;

    private final InetAddress localAddress;

    private final ConcurrentMap<InetAddress, PeerDiscoveryTransceiver> peerDiscoveryTable;
    private final ConcurrentMap<InetAddress, BlockingQueue<DiscoveryMessage>> senderQueueMap;

    private final BlockingQueue<ReceivedMessage> receiverQueue;
    private final BlockingQueue<TimestampedReplyMessage> replyQueue;

    private final Map<QueryMessageIdentifier, QueryMessageInfo> queryInfoMap;
    private final PriorityQueue<QueryMessageIdentifier> expirationQueue;

    private int nextSequenceId;

    private ListenerTask discoveryListenerTask;

    public DiscoveryServer(String directory, String trackerName) throws UnknownHostException {
        this.directory = new FileDirectory(directory);
        this.trackerName = trackerName;
        this.localAddress = InetAddress.getLocalHost();
        this.peerDiscoveryTable = new ConcurrentHashMap<>();
        this.senderQueueMap = new ConcurrentHashMap<>();
        this.receiverQueue = new LinkedBlockingQueue<>();
        this.replyQueue = new LinkedBlockingQueue<>();
        this.queryInfoMap = new HashMap<>();
        this.expirationQueue = new PriorityQueue<>((QueryMessageIdentifier lhs, QueryMessageIdentifier rhs) -> {
            long lhsExpiration = this.queryInfoMap.get(lhs).getExpiration();
            long rhsExpiration = this.queryInfoMap.get(rhs).getExpiration();
            return Long.compare(lhsExpiration, rhsExpiration);
        });
        this.nextSequenceId = 0;
    }

    public void run() {
        this.discoveryListenerTask = new DiscoveryListener();
        Thread discoveryListenerThread = new Thread(this.discoveryListenerTask);
        discoveryListenerThread.start();
        try {
            this.discoveryListenerTask.waitForReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.connectToDiscoveryNetwork();
        this.serveDiscoveryRequests();
    }

    public SearchResult queryFileByKeyword(String keyword) {
        return this.queryFile(true, keyword);
    }

    public SearchResult queryFileByFileName(String fileName) {
        return this.queryFile(false, fileName);
    }

    public SearchResult queryFile(boolean isKeywordSearch, String filter) {
        DiscoveryQueryMessage queryMessage = new DiscoveryQueryMessage();
        queryMessage.initiator = this.localAddress;
        queryMessage.isKeywordSearch = isKeywordSearch;
        queryMessage.filter = filter;

        for (int hopCount = 1; hopCount <= 16; hopCount *= 2) {
            queryMessage.hopCount = hopCount;
            queryMessage.sequenceId = this.nextSequenceId;
            this.nextSequenceId++;
            Instant expirationTime = Instant.now().plusMillis(hopCount * 250L);
            queryMessage.expiration = expirationTime.toEpochMilli();
            this.receiverQueue.add(new ReceivedMessage(queryMessage, this.localAddress));
            List<TimestampedReplyMessage> replies = this.collectResultsFor(queryMessage.sequenceId, expirationTime);
            if (!replies.isEmpty()) {
                return new SearchResult(replies, hopCount);
            }
        }

        return null;
    }

    private List<TimestampedReplyMessage> collectResultsFor(int sequenceId, Instant expirationTime) {
        List<TimestampedReplyMessage> results = new ArrayList<>();
        while (true) {
            // check time left
            Duration timeLeft = Duration.between(Instant.now(), expirationTime);
            if (timeLeft.isNegative()) {
                break;
            }
            // wait for reply until expiration
            TimestampedReplyMessage reply = null;
            try {
                reply = this.replyQueue.poll(timeLeft.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            // stop if nothing received
            if (reply == null) {
                break;
            }
            if (reply.getReplyMessage().sequenceId == sequenceId) {
                results.add(reply);
            }
        }

        return results;
    }

    private void serveDiscoveryRequests() {
        while (!Thread.interrupted()) {
            // clear expired messages
            while (!this.expirationQueue.isEmpty()) {
                QueryMessageIdentifier messageId = this.expirationQueue.peek();
                if (this.queryInfoMap.get(messageId).isExpired()) {
                    this.queryInfoMap.remove(messageId);
                    this.expirationQueue.poll();
                } else {
                    break;
                }
            }
            try {
                ReceivedMessage receivedMessage = this.receiverQueue.take();
                DiscoveryMessage message = receivedMessage.getMessage();

                // ignore expired messages
                Instant expirationTime = Instant.ofEpochMilli(message.expiration);
                if (Instant.now().isAfter(expirationTime)) {
                    continue;
                }

                // handle QUERY messages
                if (message instanceof DiscoveryQueryMessage) {
                    this.handleQueryRequest((DiscoveryQueryMessage) message, receivedMessage.getNeighborAddress());
                }

                // handle REPLY messages
                if (message instanceof DiscoveryReplyMessage) {
                    this.handleReplyRequest((DiscoveryReplyMessage) message);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }


    private void handleQueryRequest(DiscoveryQueryMessage queryMessage, InetAddress neighborAddress) {
        QueryMessageIdentifier messageId = new QueryMessageIdentifier(queryMessage.initiator, queryMessage.sequenceId);
        if (this.queryInfoMap.containsKey(messageId)) {
            // ignore duplicate messages
            return;
        }

        // insert message data and expiry
        QueryMessageInfo messageInfo = new QueryMessageInfo(queryMessage.expiration, neighborAddress);
        this.queryInfoMap.put(messageId, messageInfo);
        this.expirationQueue.add(messageId);

        // attempt to fulfill query
        FileDirectory.FileEntry fileEntry;
        if (queryMessage.isKeywordSearch) {
            fileEntry = this.directory.searchByKeyword(queryMessage.filter);
        } else {
            fileEntry = this.directory.searchByFileName(queryMessage.filter);
        }

        if (fileEntry != null) {
            // send reply if file found
            DiscoveryReplyMessage replyMessage = new DiscoveryReplyMessage();
            replyMessage.initiator = queryMessage.initiator;
            replyMessage.sequenceId = queryMessage.sequenceId;
            replyMessage.expiration = queryMessage.expiration;
            replyMessage.terminator = this.localAddress;
            replyMessage.keyword = fileEntry.keyword;
            replyMessage.fileName = fileEntry.fileName;

            if (this.localAddress.equals(neighborAddress)) {
                this.replyQueue.add(new TimestampedReplyMessage(replyMessage));
            } else {
                this.senderQueueMap.get(neighborAddress).add(replyMessage);
            }
        } else if (queryMessage.hopCount > 0) {
            // forward message if hops are available
            queryMessage.hopCount--;
            for (Map.Entry<InetAddress, BlockingQueue<DiscoveryMessage>> senderEntry : this.senderQueueMap.entrySet()) {
                InetAddress senderAddress = senderEntry.getKey();
                BlockingQueue<DiscoveryMessage> senderQueue = senderEntry.getValue();

                // do not resend to neighbor that sent query
                if (neighborAddress.equals(senderAddress)) {
                    continue;
                }

                senderQueue.add(queryMessage);
            }
        }
    }

    private void handleReplyRequest(DiscoveryReplyMessage replyMessage) {
        // send replies intended for this node to reply queue
        if (this.localAddress.equals(replyMessage.initiator)) {
            this.replyQueue.add(new TimestampedReplyMessage(replyMessage));
            return;
        }

        QueryMessageIdentifier messageId = new QueryMessageIdentifier(replyMessage.initiator, replyMessage.sequenceId);
        InetAddress neighborAddress = this.queryInfoMap.get(messageId).getNeighborAddress();
        this.senderQueueMap.get(neighborAddress).add(replyMessage);
    }

    private void addLink(Socket socket) throws IOException {
        BlockingQueue<DiscoveryMessage> senderQueue = new LinkedBlockingQueue<>();
        this.senderQueueMap.put(socket.getInetAddress(), senderQueue);
        PeerDiscoveryTransceiver transceiver =  new PeerDiscoveryTransceiver(socket, senderQueue, this.receiverQueue);
        this.peerDiscoveryTable.put(socket.getInetAddress(), transceiver);
        transceiver.start();
        System.out.println("NEIGHBORS:\n");
        for (InetAddress peerAddress : this.peerDiscoveryTable.keySet()) {
            System.out.println(peerAddress.getCanonicalHostName() + '\n');
        }
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
                byte[] peerAddress = new byte[peerAddressSize];
                trackerInput.readFully(peerAddress);
                peers.add(InetAddress.getByAddress(peerAddress));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return peers;
    }

    public static class SearchResult {
        private final List<TimestampedReplyMessage> messages;
        private final int hopCount;

        public SearchResult(List<TimestampedReplyMessage> messages, int hopCount) {
            this.hopCount = hopCount;
            this.messages = messages;
        }

        public List<TimestampedReplyMessage> getMessages() { return messages; }
        public int getHopCount() { return hopCount; }
    }

    static class ReceivedMessage {
        private final DiscoveryMessage message;
        private final InetAddress neighbor;

        public ReceivedMessage(DiscoveryMessage message, InetAddress neighbor) {
            this.message = message;
            this.neighbor = neighbor;
        }

        public DiscoveryMessage getMessage() {return this.message;}

        public InetAddress getNeighborAddress() {return this.neighbor;}
    }

    public static class TimestampedReplyMessage {
        private final DiscoveryReplyMessage replyMessage;
        private final Instant arrivalTime;

        public TimestampedReplyMessage(DiscoveryReplyMessage replyMessage) {
            this.replyMessage = replyMessage;
            this.arrivalTime = Instant.now();
        }

        public DiscoveryReplyMessage getReplyMessage() {return this.replyMessage;}
        public Instant getArrivalTime() {return this.arrivalTime;}
    }

    private static class QueryMessageIdentifier {
        private final InetAddress initiatorAddress;
        private final int sequenceId;

        public QueryMessageIdentifier(InetAddress initiatorAddress, int sequenceId) {
            this.initiatorAddress = initiatorAddress;
            this.sequenceId = sequenceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || this.getClass() != o.getClass()) return false;
            QueryMessageIdentifier that = (QueryMessageIdentifier) o;
            return this.sequenceId == that.sequenceId && Objects.equals(this.initiatorAddress, that.initiatorAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(initiatorAddress, sequenceId);
        }
    }

    private static class QueryMessageInfo {
        private final long expiration;
        private final InetAddress neighborAddress;

        public QueryMessageInfo(long expiration, InetAddress neighborAddress) {
            this.expiration = expiration;
            this.neighborAddress = neighborAddress;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(Instant.ofEpochMilli(this.expiration));
        }

        public long getExpiration() {return this.expiration;}

        public InetAddress getNeighborAddress() {return this.neighborAddress;}
    }
}
