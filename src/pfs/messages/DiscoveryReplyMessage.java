package pfs.messages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

public class DiscoveryReplyMessage extends DiscoveryMessage {
    public InetAddress terminator;
    public String keyword;
    public String fileName;

    @Override
    public void writeData(DataOutputStream out) throws IOException {
        super.writeData(out);
        byte[] terminatorAddress = terminator.getAddress();
        out.writeInt(terminatorAddress.length);
        out.write(terminatorAddress);
        out.writeUTF(keyword);
        out.writeUTF(fileName);
    }

    @Override
    public void readData(DataInputStream in) throws IOException {
        super.readData(in);
        int addressLength = in.readInt();
        byte[] terminatorAddress = in.readNBytes(addressLength);
        this.terminator = InetAddress.getByAddress(terminatorAddress);
        this.keyword = in.readUTF();
        this.fileName = in.readUTF();
    }
}
