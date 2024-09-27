package pfs.tasks;

import pfs.Constants;
import pfs.FileDirectory;
import pfs.messages.*;

import java.io.*;
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

public class Node {
    private final FileDirectory directory;
    private final String trackerName;

    private final InetAddress localAddress;

    private final ConcurrentMap<InetAddress, PeerDiscoveryTransceiver> peerDiscoveryTable;
    private final ConcurrentMap<InetAddress, BlockingQueue<Message>> senderQueueMap;

    private final BlockingQueue<ReceivedMessage> receiverQueue;
    private final BlockingQueue<TimestampedReplyMessage> replyQueue;

    private final Map<QueryMessageIdentifier, QueryMessageInfo> queryInfoMap;
    private final PriorityQueue<QueryMessageIdentifier> expirationQueue;

    private int nextSequenceId;

    private final ListenerTask discoveryListenerTask;
    private final Thread discoveryServerThread;
    private final ListenerTask transferServerTask;

    public Node(String directory, String trackerName) throws UnknownHostException {
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
        this.discoveryListenerTask = new DiscoveryListener();
        this.discoveryServerThread = new Thread(this::serveRequests);
        this.discoveryServerThread.setName("discovery-server");
        this.transferServerTask = new TransferServer();
    }

    public void start() {
        Thread discoveryListenerThread = new Thread(discoveryListenerTask);
        discoveryListenerThread.setName("discovery-listener");
        discoveryListenerThread.start();
        try {
            this.discoveryListenerTask.waitForReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Thread transferServerThread = new Thread(this.transferServerTask);
        transferServerThread.setName("transfer-server");
        transferServerThread.start();
        try {
            this.transferServerTask.waitForReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.connectToPeer(this.getRandomPeer());
        this.discoveryServerThread.start();
    }

    public void stop() throws IOException, InterruptedException {
        this.notifyLeave();
        this.discoveryListenerTask.stop();
        this.transferServerTask.stop();
        this.discoveryServerThread.interrupt();
        this.handoffNeighbors();
        Thread.sleep(500);
        for (PeerDiscoveryTransceiver transceiver : this.peerDiscoveryTable.values()) {
            transceiver.stop();
        }
    }

    private void handoffNeighbors() {
        Random random = new Random();
        InetAddress[] neighbors = this.senderQueueMap.keySet().toArray(new InetAddress[0]);
        int index = random.nextInt(neighbors.length);
        InetAddress handoffAddress = neighbors[index];
        HangupMessage hangupMessage = new HangupMessage();
        hangupMessage.handoffAddress = handoffAddress;
        for (BlockingQueue<Message> senderQueue : this.senderQueueMap.values()) {
            senderQueue.add(hangupMessage);
        }
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

    private void serveRequests() {
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
                Message message = receivedMessage.getMessage();

                if (message instanceof DiscoveryMessage) {
                    // ignore expired messages
                    Instant expirationTime = Instant.ofEpochMilli(((DiscoveryMessage) message).expiration);
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
                } else if (message instanceof HangupMessage) {
                    // handle HANGUP messages
                    InetAddress handoffAddress = ((HangupMessage) message).handoffAddress;
                    PeerDiscoveryTransceiver transceiver = this.peerDiscoveryTable.remove(receivedMessage.getNeighborAddress());
                    try {
                        if (transceiver != null) {
                            transceiver.stop();
                        }
                    } catch (IOException ignored) {
                    }
                    this.senderQueueMap.remove(receivedMessage.getNeighborAddress());
                    if (!this.localAddress.equals(handoffAddress)) {
                        this.connectToPeer(handoffAddress);
                    }
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
            for (Map.Entry<InetAddress, BlockingQueue<Message>> senderEntry : this.senderQueueMap.entrySet()) {
                InetAddress senderAddress = senderEntry.getKey();
                BlockingQueue<Message> senderQueue = senderEntry.getValue();

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
        BlockingQueue<Message> senderQueue = new LinkedBlockingQueue<>();
        this.senderQueueMap.put(socket.getInetAddress(), senderQueue);
        PeerDiscoveryTransceiver transceiver = new PeerDiscoveryTransceiver(socket, senderQueue, this.receiverQueue);
        this.peerDiscoveryTable.put(socket.getInetAddress(), transceiver);
        transceiver.start();
        StringBuilder sb = new StringBuilder("NEIGHBORS:\n");
        for (InetAddress peerAddress : this.peerDiscoveryTable.keySet()) {
            sb.append(peerAddress.getCanonicalHostName()).append('\n');
        }
        System.out.println(sb);
    }

    private void connectToPeer(InetAddress peerAddress) {
        if (peerAddress == null) {
            return;
        }
        try {
            Socket discoverySocket = new Socket(peerAddress, Constants.DISCOVERY_PORT);
            System.out.println("CONNECT TO: " + discoverySocket.getInetAddress().getCanonicalHostName());
            this.addLink(discoverySocket);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void notifyLeave() {
        try (
                Socket trackerSocket = new Socket(this.trackerName, Constants.TRACKER_PORT);
                DataOutputStream trackerOutput = new DataOutputStream(trackerSocket.getOutputStream())
        ) {
            trackerOutput.writeByte(1);
            trackerOutput.flush();
            System.out.println("Notifying LEAVE");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private InetAddress getRandomPeer() {
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
        Random random = new Random();
        if (peers.isEmpty()) {
            return null;
        }
        int index = random.nextInt(peers.size());
        return peers.get(index);
    }

    public void transferFile(InetAddress target, String fileName, String keyword) throws IOException {
        try (
                Socket socket = new Socket(target, Constants.TRANSFER_PORT);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())
        ) {
            out.writeUTF(fileName);
            out.flush();

            long contentLength = in.readLong();
            this.directory.createFile(fileName, keyword, contentLength);
            try (OutputStream fileWriter = this.directory.newFileOutput(fileName)) {
                byte[] buffer = new byte[4096];
                while (contentLength > 0) {
                    int bytesRead = in.read(buffer);
                    fileWriter.write(buffer, 0, bytesRead);
                    contentLength -= bytesRead;
                }
                System.out.println("Download complete!");
            }
        }
    }

    private class DiscoveryListener extends ListenerTask {
        public DiscoveryListener() {
            super(Constants.DISCOVERY_PORT);
        }

        @Override
        protected void handleConnection(Socket socket) throws IOException {
            System.out.println("CONNECT FROM: " + socket.getInetAddress().getCanonicalHostName());
            Node.this.addLink(socket);
        }
    }

    private class TransferServer extends ListenerTask {
        public TransferServer() {super(Constants.TRANSFER_PORT);}

        @Override
        protected void handleConnection(Socket socket) {
            Thread handlerThread = new Thread(() -> {
                try {
                    this.serveRequest(socket);
                } catch (IOException ignored) {
                }
            });
            handlerThread.start();
        }

        private void serveRequest(Socket socket) throws IOException {
            try (
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream())
            ) {
                socket.setSoTimeout(200);
                String fileName = in.readUTF();
                FileDirectory.FileEntry entry = Node.this.directory.searchByFileName(fileName);
                if (entry == null) {
                    return;
                }

                long contentLength = entry.contentLength;
                out.writeLong(contentLength);
                try (InputStream fileReader = Node.this.directory.newFileInput(fileName)) {
                    byte[] buffer = new byte[4096];
                    while (contentLength > 0) {
                        int bytesRead = fileReader.read(buffer);
                        out.write(buffer, 0, bytesRead);
                        contentLength -= bytesRead;
                    }
                }
            } finally {
                socket.close();
            }
        }
    }

    public static class SearchResult {
        private final List<TimestampedReplyMessage> messages;
        private final int hopCount;

        public SearchResult(List<TimestampedReplyMessage> messages, int hopCount) {
            this.hopCount = hopCount;
            this.messages = messages;
        }

        public List<TimestampedReplyMessage> getMessages() {return messages;}

        public int getHopCount() {return hopCount;}
    }

    public static class ReceivedMessage {
        private final Message message;
        private final InetAddress neighbor;

        public ReceivedMessage(Message message, InetAddress neighbor) {
            this.message = message;
            this.neighbor = neighbor;
        }

        public Message getMessage() {return this.message;}

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
