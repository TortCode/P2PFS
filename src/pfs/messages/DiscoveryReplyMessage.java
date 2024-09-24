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
        Message.writeBytes(out, terminator.getAddress());
        out.writeUTF(keyword);
        out.writeUTF(fileName);
    }

    @Override
    public void readData(DataInputStream in) throws IOException {
        super.readData(in);
        this.terminator = InetAddress.getByAddress(Message.readBytes(in));
        this.keyword = in.readUTF();
        this.fileName = in.readUTF();
    }
}
