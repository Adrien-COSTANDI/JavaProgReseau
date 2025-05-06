package chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public class ChatonServer {

  public static final int PORT = 9999;
  private static final int BUFFER_SIZE = 1024;
  private static final Logger logger = Logger.getLogger(ChatonServer.class.getName());
  private final ServerSocketChannel serverSocketChannel;
  private final Selector selector;

  public ChatonServer() throws IOException {
    serverSocketChannel = ServerSocketChannel.open();
    selector = Selector.open();
    serverSocketChannel.bind(new InetSocketAddress(PORT));
  }

  public static void main(String[] args) throws IOException {
    new ChatonServer().start();
  }

  public void start() throws IOException {
    serverSocketChannel.configureBlocking(false);
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

    while (!Thread.interrupted()) {
      System.out.println("Starting select");
      try {
        selector.select(this::treatKey);
      } catch (UncheckedIOException tunneled) {
        throw tunneled.getCause();
      }
      System.out.println("Select finished");
    }
  }

  private void broadcast(Message msg) {
    selector.keys().stream()
        .filter(key -> key.channel() instanceof SocketChannel)
        .forEach(key -> {
          var context = (Context) key.attachment();
          context.queueMessage(msg);
        });
  }

  private void treatKey(SelectionKey key) {
    try {
      if (key.isValid() && key.isAcceptable()) {
        doAccept(key);
      }
    } catch (IOException ioe) {
      // lambda call in select requires to tunnel IOException
      throw new UncheckedIOException("Entrance door is on fire!", ioe);
    }
    try {
      if (key.isValid() && key.isWritable()) {
        ((Context) key.attachment()).doWrite();
      }
      if (key.isValid() && key.isReadable()) {
        ((Context) key.attachment()).doRead();
      }
    } catch (IOException e) {
      logger.log(Level.INFO, "Connection closed with client due to IOException");
      silentlyClose(key);
    }
  }

  private void silentlyClose(SelectionKey key) {
    Channel sc = key.channel();
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }

  private void doAccept(SelectionKey key) throws IOException {
    var client = serverSocketChannel.accept();

    if (client == null) {
      return;
    }
    client.configureBlocking(false);
    var selectionKey = client.register(selector, OP_READ);
    selectionKey.attach(new Context(this, selectionKey));

    System.out.println("new client: " + client.getRemoteAddress());
  }

  private static class Context {

    private final SelectionKey key;
    private final SocketChannel sc;
    private final ChatonServer server;

    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<Message> queue = new ArrayDeque<>();

    private final MessageReader messageReader = new MessageReader();

    private boolean closed = false;

    private Context(ChatonServer server, SelectionKey key) {
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
    private void doRead() throws IOException {
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
    private void doWrite() throws IOException {
      bufferOut.flip();
      sc.write(bufferOut);
      bufferOut.compact();
      processOut();
      updateInterestOps();
    }

  }
}
