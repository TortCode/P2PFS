import messages.DiscoveryMessage;
import messages.DiscoveryQueryMessage;
import messages.DiscoveryReplyMessage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class PeerDiscoveryLink {
    private final Socket socket;
    private final DataOutputStream outputStream;
    private final DataInputStream inputStream;
    private final BlockingQueue<DiscoveryMessage> senderQueue;
    private final BlockingQueue<DiscoveryMessage> receiverQueue;
    private final BlockingQueue<DiscoveryQueryMessage> queryQueue;
    private final BlockingQueue<DiscoveryReplyMessage> replyQueue;

    public PeerDiscoveryLink(
            Socket socket,
            BlockingQueue<DiscoveryQueryMessage> queryQueue,
            BlockingQueue<DiscoveryReplyMessage> replyQueue
    ) throws IOException {
        this.socket = socket;
        this.outputStream = new DataOutputStream(socket.getOutputStream());
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.senderQueue = new LinkedBlockingQueue<>();
        this.receiverQueue = new LinkedBlockingQueue<>();
        this.queryQueue = queryQueue;
        this.replyQueue = replyQueue;
    }

    private static void logMessage(DiscoveryMessage message, String eventType) {
        LocalDateTime expirationTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.expiration), ZoneId.systemDefault());
        System.out.format("[%s] %s | ", LocalDateTime.now(), eventType);
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
                DiscoveryMessage message = PeerDiscoveryLink.this.senderQueue.take();
                boolean isReply = (message instanceof DiscoveryReplyMessage);
                PeerDiscoveryLink.this.outputStream.writeBoolean(isReply);
                message.writeData(PeerDiscoveryLink.this.outputStream);
                PeerDiscoveryLink.logMessage(message, "SEND");
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }

    private void runReceiver() {
        while (!Thread.interrupted()) {
            try {
                boolean isReply = PeerDiscoveryLink.this.inputStream.readBoolean();
                DiscoveryMessage message = (isReply) ? new DiscoveryReplyMessage() : new DiscoveryQueryMessage();
                message.readData(PeerDiscoveryLink.this.inputStream);
                PeerDiscoveryLink.logMessage(message, "RECV");
                PeerDiscoveryLink.this.receiverQueue.put(message);
            } catch (InterruptedException ignore) {
                return;
            } catch (IOException ignore) {
            }
        }
    }
}
