package pfs.tasks;

import pfs.messages.*;

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
    private final BlockingQueue<Message> senderQueue;
    private final BlockingQueue<Node.ReceivedMessage> receiverQueue;
    private final Thread senderThread;
    private final Thread receiverThread;

    public PeerDiscoveryTransceiver(
            Socket socket,
            BlockingQueue<Message> senderQueue,
            BlockingQueue<Node.ReceivedMessage> receiverQueue
    ) throws IOException {
        this.socket = socket;
        this.outputStream = new DataOutputStream(socket.getOutputStream());
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.senderQueue = senderQueue;
        this.receiverQueue = receiverQueue;
        this.senderThread = new Thread(this::runSender);
        this.senderThread.setName(socket.getInetAddress().getCanonicalHostName() + ":sender");
        this.receiverThread = new Thread(this::runReceiver);
        this.receiverThread.setName(socket.getInetAddress().getCanonicalHostName() + ":receiver");
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

    private void logMessage(Message message, String eventType) {
        System.out.format("[%s] %s VIA %s | ", LocalDateTime.now(), eventType, this.socket.getInetAddress().getCanonicalHostName());
        if (message instanceof DiscoveryMessage) {
            DiscoveryMessage discoveryMessage = (DiscoveryMessage) message;
            LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(discoveryMessage.expiration), ZoneId.systemDefault());
            System.out.format("DISC %s (%d) EXPIRES %s | ", discoveryMessage.initiator.getCanonicalHostName(), discoveryMessage.sequenceId, expirationTime);
            if (message instanceof DiscoveryQueryMessage) {
                DiscoveryQueryMessage queryMessage = (DiscoveryQueryMessage) discoveryMessage;
                System.out.format("QUERY %s: %s | ", (queryMessage.isKeywordSearch) ? "KW" : "FN", queryMessage.filter);
                System.out.format("HOPCOUNT %d", queryMessage.hopCount);
            } else if (message instanceof DiscoveryReplyMessage) {
                DiscoveryReplyMessage replyMessage = (DiscoveryReplyMessage) discoveryMessage;
                System.out.format("REPLY KW: %s FN: %s | ", replyMessage.keyword, replyMessage.fileName);
                System.out.format("TERMINATOR %s", replyMessage.terminator.getCanonicalHostName());
            }
        } else if (message instanceof HangupMessage) {
            HangupMessage hangupMessage = (HangupMessage) message;
            System.out.format("HUP HANDOFF %s", hangupMessage.handoffAddress.getCanonicalHostName());
        }
        System.out.println();
    }

    private static byte getTypeOfMessage(Message message) {
        if (message instanceof DiscoveryQueryMessage) {
            return 0;
        } else if (message instanceof DiscoveryReplyMessage) {
            return 1;
        } else if (message instanceof HangupMessage) {
            return 2;
        }
        throw new IllegalArgumentException();
    }

    private static Message getMessageOfType(byte type) {
        switch (type) {
            case 0:
                return new DiscoveryQueryMessage();
            case 1:
                return new DiscoveryReplyMessage();
            case 2:
                return new HangupMessage();
        }
        throw new IllegalArgumentException();
    }

    private void runSender() {
        while (!Thread.interrupted()) {
            try {
                Message message = this.senderQueue.take();
                byte type = PeerDiscoveryTransceiver.getTypeOfMessage(message);
                this.outputStream.writeByte(type);
                message.writeData(this.outputStream);
                this.outputStream.flush();
                this.logMessage(message, "SEND");
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }

    private void runReceiver() {
        while (!Thread.interrupted()) {
            try {
                byte type = this.inputStream.readByte();
                Message message = PeerDiscoveryTransceiver.getMessageOfType(type);
                message.readData(this.inputStream);
                this.logMessage(message, "RECV");
                this.receiverQueue.put(new Node.ReceivedMessage(message, this.socket.getInetAddress()));
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }
}
