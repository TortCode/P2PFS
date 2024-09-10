package pfs.tasks;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

public abstract class ListenerTask implements Runnable {
    private volatile ServerSocket serverSocket;
    private final int port;
    private final CountDownLatch readyLatch;

    public ListenerTask(int port) {
        this.port = port;
        this.readyLatch = new CountDownLatch(1);
    }

    public void waitForReady() throws InterruptedException {
        this.readyLatch.await();
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(this.port)) {
            this.serverSocket = serverSocket;
            this.readyLatch.countDown();
            while (!Thread.interrupted()) {
                try {
                    Socket socket = this.serverSocket.accept();
                    this.handleConnection(socket);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract void handleConnection(Socket socket) throws IOException;

    public void stop() throws IOException {
        if (this.serverSocket != null) {
            this.serverSocket.close();
        }
    }
}
