package pfs;

import pfs.tasks.DiscoveryServer;
import pfs.tasks.TrackerServer;

import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws UnknownHostException {
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
            Thread discoveryServerThread = new Thread(discoveryServer);
            discoveryServerThread.start();

            Scanner sc = new Scanner(System.in);

            int choice;
            do {
                System.out.println("select:");
                System.out.println("1) search by keyword");
                System.out.println("2) search by filename");

                String token;
                while (true) {
                    token = sc.next();
                    try {
                        choice = Integer.parseInt(token);
                        if (choice >= 1 && choice <= 2) {
                            break;
                        } else {
                            System.out.println("number must be between 1 and 3: please reenter");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("invalid number: please reenter");
                    }
                }

                DiscoveryServer.SearchResult result = null;
                Instant startTime = Instant.now();
                switch (choice) {
                    case 1:
                        System.out.println("enter keyword:");
                        String keyword = sc.next();
                        result = discoveryServer.queryFileByKeyword(keyword);
                        break;
                    case 2:
                        System.out.println("enter filename:");
                        String fileName = sc.next();
                        result = discoveryServer.queryFileByFileName(fileName);
                        break;
                }

                if (result == null) {
                    System.out.println("Query failed");
                } else {
                    System.out.println("Query succeeded with HOPCOUNT: " + result.getHopCount());
                    for (DiscoveryServer.TimestampedReplyMessage message : result.getMessages()) {
                        long elapsedMillis = Duration.between(startTime, message.getArrivalTime()).toMillis();
                        System.out.printf("[%d ms]: %s\n", elapsedMillis, message.getReplyMessage().terminator.getCanonicalHostName());
                    }
                }
            } while (true);
        }
    }
}