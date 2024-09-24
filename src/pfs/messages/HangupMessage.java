package pfs.messages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;

public class HangupMessage implements Message {
    public InetAddress handoffAddress;
    @Override
    public void writeData(DataOutputStream out) throws IOException {
        Message.writeBytes(out, handoffAddress.getAddress());
    }

    @Override
    public void readData(DataInputStream in) throws IOException {
        this.handoffAddress = InetAddress.getByAddress(Message.readBytes(in));
    }
}
