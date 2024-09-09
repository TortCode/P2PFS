public class Main {
    public static void main(String[] args) {
        if (args.length != 0 && args.length != 2) {
            System.err.println("Usage: java Main (for tracker)");
            System.err.println("Usage: java Main <directory> <trackername> (for node)");
            System.exit(1);
        }

        if (args.length == 0) {
            TrackerServer trackerServer = new TrackerServer();
            trackerServer.run();
        } else if (args.length == 2) {
            Node node = new Node(args[0], args[1]);
            node.start();
        }
    }
}