package pfs.tasks;

import pfs.messages.DiscoveryMessage;
import pfs.messages.DiscoveryQueryMessage;
import pfs.messages.DiscoveryReplyMessage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.BlockingQueue;

public class PeerDiscoveryTransceiver {
    private final Socket socket;
    private final DataOutputStream outputStream;
    private final DataInputStream inputStream;
    private final BlockingQueue<DiscoveryMessage> senderQueue;
    private final BlockingQueue<DiscoveryServer.ReceivedMessage> receiverQueue;
    private final Thread senderThread;
    private final Thread receiverThread;

    public PeerDiscoveryTransceiver(
            Socket socket,
            BlockingQueue<DiscoveryMessage> senderQueue,
            BlockingQueue<DiscoveryServer.ReceivedMessage> receiverQueue
    ) throws IOException {
        this.socket = socket;
        this.outputStream = new DataOutputStream(socket.getOutputStream());
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.senderQueue = senderQueue;
        this.receiverQueue = receiverQueue;
        this.senderThread = new Thread(this::runSender);
        this.receiverThread = new Thread(this::runReceiver);
    }

    public void start() {
        this.senderThread.start();
        this.receiverThread.start();
    }

    public void stop() throws IOException {
        this.senderThread.interrupt();
        this.receiverThread.interrupt();
        this.socket.close();
    }

    private void logMessage(DiscoveryMessage message, String eventType) {
        LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.expiration), ZoneId.systemDefault());
        System.out.format("[%s] %s VIA %s | ", LocalDateTime.now(), eventType, this.socket.getInetAddress().getCanonicalHostName());
        System.out.format("%s (%d) EXPIRES %s | ", message.initiator.getCanonicalHostName(), message.sequenceId, expirationTime);
        if (message instanceof DiscoveryQueryMessage) {
            DiscoveryQueryMessage queryMessage = (DiscoveryQueryMessage) message;
            System.out.format("QUERY %s: %s | ", (queryMessage.isKeywordSearch) ? "KW" : "FN", queryMessage.filter);
            System.out.format("HOPCOUNT %d", queryMessage.hopCount);
        }
        if (message instanceof DiscoveryReplyMessage) {
            DiscoveryReplyMessage replyMessage = (DiscoveryReplyMessage) message;
            System.out.format("REPLY KW: %s FN: %s | ", replyMessage.keyword, replyMessage.fileName);
            System.out.format("TERMINATOR %s", replyMessage.terminator.getCanonicalHostName());
        }
        System.out.println();
    }

    private void runSender() {
        while (!Thread.interrupted()) {
            try {
                DiscoveryMessage message = PeerDiscoveryTransceiver.this.senderQueue.take();
                boolean isReply = (message instanceof DiscoveryReplyMessage);
                PeerDiscoveryTransceiver.this.outputStream.writeBoolean(isReply);
                message.writeData(PeerDiscoveryTransceiver.this.outputStream);
                PeerDiscoveryTransceiver.this.outputStream.flush();
                PeerDiscoveryTransceiver.this.logMessage(message, "SEND");
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }

    private void runReceiver() {
        while (!Thread.interrupted()) {
            try {
                boolean isReply = PeerDiscoveryTransceiver.this.inputStream.readBoolean();
                DiscoveryMessage message = (isReply) ? new DiscoveryReplyMessage() : new DiscoveryQueryMessage();
                message.readData(PeerDiscoveryTransceiver.this.inputStream);
                PeerDiscoveryTransceiver.this.logMessage(message, "RECV");
                PeerDiscoveryTransceiver.this.receiverQueue.put(new DiscoveryServer.ReceivedMessage(message, this.socket.getInetAddress()));
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }
}
