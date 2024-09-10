package pfs.messages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

public abstract class DiscoveryMessage {
    public InetAddress initiator;
    public int sequenceId;
    public long expiration; // specified in epoch milliseconds

    private static int nextSequenceId = 0;

    public void writeData(DataOutputStream out) throws IOException {
        byte[] initiatorAddress = initiator.getAddress();
        out.writeInt(initiatorAddress.length);
        out.write(initiatorAddress);
        out.writeInt(sequenceId);
        out.writeLong(expiration);
    }

    public void readData(DataInputStream in) throws IOException {
        int addressLength = in.readInt();
        byte[] initiatorAddress = in.readNBytes(addressLength);
        this.initiator = InetAddress.getByAddress(initiatorAddress);
        this.sequenceId = in.readInt();
        this.expiration = in.readLong();
    }
}

