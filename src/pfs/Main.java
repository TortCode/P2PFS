package pfs;

import pfs.tasks.DiscoveryServer;
import pfs.tasks.TrackerServer;

public class Main {
    public static void main(String[] args) {
        if (args.length != 0 && args.length != 2) {
            System.err.println("Usage: java pfs.Main (for tracker)");
            System.err.println("Usage: java pfs.Main <directory> <trackername> (for node)");
            System.exit(1);
        }

        if (args.length == 0) {
            TrackerServer trackerServer = new TrackerServer();
            trackerServer.run();
        } else if (args.length == 2) {
            DiscoveryServer discoveryServer = new DiscoveryServer(args[0], args[1]);
            discoveryServer.start();
        }
    }
}