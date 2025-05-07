package chat.multithread;

import chat.Message;
import chat.MessageReader;
import chat.Reader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public class ChatonServerMultiThread {

  public static final int PORT = 9999;
  private static final int BUFFER_SIZE = 1024;
  private static final Logger logger = Logger.getLogger(ChatonServerMultiThread.class.getName());
  private final ServerSocketChannel serverSocketChannel;

  private final static int NB_THREADS_SERVER = 3;
  private final ArrayList<Thread> threads = new ArrayList<>(NB_THREADS_SERVER);
  private final ArrayList<NetworkThread> networkThreads = new ArrayList<>(NB_THREADS_SERVER);

  private int roundRobinIndex = 0;

  public ChatonServerMultiThread() throws IOException {

    for (int i = 0; i < NB_THREADS_SERVER; i++) {
      String name = "network-thread-" + i;
      var networkThread = new NetworkThread(name);
      threads.add(Thread.ofPlatform()
          .name(name)
          .unstarted(networkThread)
      );
      networkThreads.add(networkThread);
    }
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.bind(new InetSocketAddress(PORT));
  }

  public static void main(String[] args) throws IOException {
    new ChatonServerMultiThread().start();
  }

  public void start() {
    threads.forEach(Thread::start);

    while (!Thread.interrupted()) {
      try {
        var client = serverSocketChannel.accept();
        System.out.println("Connection accepted from " + client.getRemoteAddress());
        giveToAThread(client);
      } catch (IOException e) {
        logger.severe("server socket is dead, call 911");
        System.exit(1);
        return;
      }
    }
    logger.severe("Main thread is dead :(");
  }

  private void giveToAThread(SocketChannel client) {
    networkThreads.get(roundRobinIndex).takeCareOf(client, this);
    roundRobinIndex = (roundRobinIndex + 1) % NB_THREADS_SERVER;
  }

  private void broadcast(Message msg) { // broadcast tous les threads
    for (NetworkThread networkThread : networkThreads) {
      networkThread.localBroadcast(msg);
    }
  }

  public static class Context {

    private final SelectionKey key;
    private final SocketChannel sc;
    private final ChatonServerMultiThread server;

    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<Message> queue = new ArrayDeque<>();

    private final MessageReader messageReader = new MessageReader();

    private boolean closed = false;

    Context(ChatonServerMultiThread server, SelectionKey key) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
      this.server = server;
    }

    private void processIn() {
      if (bufferIn.position() < 2 * Integer.BYTES) {
        return;
      }

      Reader.ProcessStatus status;

      while ((status = messageReader.process(bufferIn)) == Reader.ProcessStatus.DONE) {
        var message = messageReader.get();
        server.broadcast(message);
        messageReader.reset();
      }
      if (status == Reader.ProcessStatus.ERROR) {
        silentlyClose();
      }
    }

    /**
     * Add a message to the message queue, tries to fill bufferOut and updateInterestOps
     *
     * @param msg
     */
    public void queueMessage(Message msg) {
      queue.add(msg);
      processOut();
      updateInterestOps();
    }

    /**
     * Try to fill bufferOut from the message queue
     */
    private void processOut() {
      while (!queue.isEmpty() && bufferOut.remaining() >= queue.peek().size()) {
        bufferOut.put(queue.poll().toByteBuffer());
      }
    }

    /**
     * Update the interestOps of the key looking only at values of the boolean
     * closed and of both ByteBuffers.
     *
     * The convention is that both buffers are in write-mode before the call to
     * updateInterestOps and after the call. Also it is assumed that process has
     * been be called just before updateInterestOps.
     */
    private void updateInterestOps() {
      var ops = 0; // à la base

      if (!closed && bufferIn.hasRemaining()) { // si je peux encore lire
        ops |= OP_READ;
      }

      if (bufferOut.position() != 0) { // s'il me reste des choses à écrire
        ops |= OP_WRITE;
      }

      if (ops == 0) { // au final si j'ai rien à faire
        silentlyClose();
        return;
      }

      key.interestOps(ops);
    }

    private void silentlyClose() {
      try {
        sc.close();
      } catch (IOException e) {
        // ignore exception
      }
    }

    /**
     * Performs the read action on sc
     *
     * The convention is that both buffers are in write-mode before the call to
     * doRead and after the call
     *
     * @throws IOException
     */
    void doRead() throws IOException {
      if (sc.read(bufferIn) == -1) {
        closed = true;
      }
      processIn();
      updateInterestOps();
    }

    /**
     * Performs the write action on sc
     *
     * The convention is that both buffers are in write-mode before the call to
     * doWrite and after the call
     *
     * @throws IOException
     */
    void doWrite() throws IOException {
      bufferOut.flip();
      sc.write(bufferOut);
      bufferOut.compact();
      processOut();
      updateInterestOps();
    }

  }
}
