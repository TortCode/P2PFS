package pfs;

import pfs.messages.DiscoveryReplyMessage;
import pfs.tasks.Node;
import pfs.tasks.TrackerServer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0 && args.length != 2) {
            System.err.println("Usage: java pfs.Main (for tracker)");
            System.err.println("Usage: java pfs.Main <directory> <trackername> (for node)");
            System.exit(1);
        }

        if (args.length == 0) {
            TrackerServer trackerServer = new TrackerServer();
            trackerServer.run();
        } else if (args.length == 2) {
            Node node = new Node(args[0], args[1]);
            node.start();

            Scanner sc = new Scanner(System.in);

            int choice;
            while (true) {
                String menuOptions = "select:\n" +
                        "1) search by keyword\n" +
                        "2) search by filename\n" +
                        "3) quit\n";
                System.out.println(menuOptions);

                String token;
                while (true) {
                    token = sc.next();
                    try {
                        choice = Integer.parseInt(token);
                        if (choice >= 1 && choice <= 3) {
                            break;
                        } else {
                            System.out.format("number must be between 1 and 3: please reenter");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("invalid number: please reenter");
                    }
                }

                Node.SearchResult result = null;
                Instant startTime = null;
                switch (choice) {
                    case 1:
                        System.out.println("enter keyword:");
                        String keyword = sc.next();
                        startTime = Instant.now();
                        result = node.queryFileByKeyword(keyword);
                        break;
                    case 2:
                        System.out.println("enter filename:");
                        String fileName = sc.next();
                        startTime = Instant.now();
                        result = node.queryFileByFileName(fileName);
                        break;
                    case 3:
                        System.out.println("exiting...");
                        node.stop();
                        return;
                }

                if (result == null) {
                    System.out.println("SEARCH FAIL\n");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("SEARCH SUCCESS | HOPCOUNT: ");
                    sb.append(result.getHopCount());
                    sb.append('\n');
                    int i = 1;
                    for (Node.TimestampedReplyMessage message : result.getMessages()) {
                        long elapsedMillis = Duration.between(startTime, message.getArrivalTime()).toMillis();
                        sb.append(String.format("%d) [%d ms]: %s\n", i, elapsedMillis, message.getReplyMessage().terminator.getCanonicalHostName()));
                        i++;
                    }
                    System.out.println(sb);

                    System.out.println("Enter choice:");
                    int transferChoice;
                    while (true) {
                        token = sc.next();
                        try {
                            transferChoice = Integer.parseInt(token) - 1;
                            if (transferChoice >= 0 && transferChoice < result.getMessages().size()) {
                                break;
                            } else {
                                System.out.format("number must be between 1 and %d: please reenter\n", result.getMessages().size());
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("invalid number: please reenter");
                        }
                    }

                    DiscoveryReplyMessage replyMessage = result.getMessages().get(transferChoice).getReplyMessage();
                    node.transferFile(replyMessage.terminator, replyMessage.fileName, replyMessage.keyword);
                }
            }
        }
    }
}