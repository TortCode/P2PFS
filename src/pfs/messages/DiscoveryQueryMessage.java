package pfs.messages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class DiscoveryQueryMessage extends DiscoveryMessage {
    public int hopCount;
    public boolean isKeywordSearch;
    public String filter;

    @Override
    public void writeData(DataOutputStream out) throws IOException {
        super.writeData(out);
        out.writeInt(hopCount);
        out.writeBoolean(isKeywordSearch);
        out.writeUTF(filter);
    }

    @Override
    public void readData(DataInputStream in) throws IOException {
        super.readData(in);
        this.hopCount = in.readInt();
        this.isKeywordSearch = in.readBoolean();
        this.filter = in.readUTF();
    }
}
