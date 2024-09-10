package pfs.tasks;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import pfs.Constants;

public class TrackerServer extends ListenerTask {
    private final List<InetAddress> peers;

    public TrackerServer() {
        super(Constants.TRACKER_PORT);
        this.peers = new ArrayList<>();
    }

    @Override
    protected void handleConnection(Socket socket) throws IOException {
        try (
                socket;
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())
        ) {
            byte message = in.readByte();
            if (message == 0) {
                // trying to join network
                out.writeInt(this.peers.size());
                for (InetAddress peer : this.peers) {
                    byte[] peerAddress = peer.getAddress();
                    out.writeInt(peerAddress.length);
                    out.write(peerAddress);
                }
                out.flush();
                this.peers.add(socket.getInetAddress());
                System.out.println("TRACKING: " + socket.getInetAddress().getCanonicalHostName());
            }
        }
    }
}
