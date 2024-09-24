package pfs.messages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

public abstract class DiscoveryMessage implements Message {
    public InetAddress initiator;
    public int sequenceId;
    public long expiration; // specified in epoch milliseconds

    @Override
    public void writeData(DataOutputStream out) throws IOException {
        Message.writeBytes(out, initiator.getAddress());
        out.writeInt(sequenceId);
        out.writeLong(expiration);
    }

    @Override
    public void readData(DataInputStream in) throws IOException {
        this.initiator = InetAddress.getByAddress(Message.readBytes(in));
        this.sequenceId = in.readInt();
        this.expiration = in.readLong();
    }
}

