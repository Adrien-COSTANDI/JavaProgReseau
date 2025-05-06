package chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Scanner;
import java.util.logging.Logger;

public class ClientChaton {

  private static final int BUFFER_SIZE = 10_000;
  private static final Logger logger = Logger.getLogger(ClientChaton.class.getName());
  private final SocketChannel sc;
  private final Selector selector;
  private final InetSocketAddress serverAddress;
  private final String login;
  private final Thread console;
  private final Slide<String> consoleSlide;
  private Context uniqueContext;
  public ClientChaton(String login, InetSocketAddress serverAddress) throws IOException {
    this.serverAddress = serverAddress;
    this.login = login;
    this.sc = SocketChannel.open();
    this.selector = Selector.open();
    this.consoleSlide = new Slide<>();
    this.console = Thread.ofPlatform().unstarted(new Console(consoleSlide, selector));
  }

  public static void main(String[] args) throws NumberFormatException, IOException {
    if (args.length != 3) {
      usage();
      return;
    }
    new ClientChaton("Bob", new InetSocketAddress("localhost", 9999)).launch();
  }

  private static void usage() {
    System.out.println("Usage : ClientChat login hostname port");
  }

  /**
   * Processes the command from the BlockingQueue
   */
  private void processCommands() throws InterruptedException {
    while (!consoleSlide.isEmpty()) {
      var command = consoleSlide.take();
      uniqueContext.queueMessage(new Message(login, command));
    }
  }

  public void launch() throws IOException {
    sc.configureBlocking(false);
    var key = sc.register(selector, SelectionKey.OP_CONNECT);
    uniqueContext = new Context(key);
    key.attach(uniqueContext);
    sc.connect(serverAddress);

    console.start();

    while (!Thread.interrupted()) {
      try {
        selector.select(this::treatKey);
        processCommands();
      } catch (UncheckedIOException tunneled) {
        throw tunneled.getCause();
      } catch (InterruptedException e) {
        logger.warning("Console thread is dead");
      }
    }
  }

  private void treatKey(SelectionKey key) {
    try {
      if (key.isValid() && key.isConnectable()) {
        uniqueContext.doConnect();
      }
      if (key.isValid() && key.isWritable()) {
        uniqueContext.doWrite();
      }
      if (key.isValid() && key.isReadable()) {
        uniqueContext.doRead();
      }
    } catch (IOException ioe) {
      // lambda call in select requires to tunnel IOException
      throw new UncheckedIOException(ioe);
    }
  }

  private void silentlyClose(SelectionKey key) {
    var sc = (Channel) key.channel();
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }

  private static class Slide<T> {

    private final Object lock = new Object();
    private final ArrayDeque<T> queuedMessages = new ArrayDeque<>();

    public void put(T msg) {
      Objects.requireNonNull(msg);
      synchronized (lock) {
        queuedMessages.add(msg);
        lock.notify();
      }
    }

    public boolean isEmpty() {
      synchronized (lock) {
        return queuedMessages.isEmpty();
      }
    }

    public T take() throws InterruptedException {
      synchronized (lock) {
        while (queuedMessages.isEmpty()) {
          lock.wait();
        }

        var message = queuedMessages.poll();

        if (message == null) {
          throw new AssertionError("Message is null");
        }

        return message;
      }
    }
  }

  private static class Console implements Runnable {

    private final static Logger logger = Logger.getLogger(Console.class.getName());
    private final Slide<String> messages;
    private final Selector selector;

    public Console(Slide<String> messages, Selector selector) {
      this.messages = Objects.requireNonNull(messages);
      this.selector = selector;
    }

    private void sendCommand(String msg) {
      Objects.requireNonNull(msg);
      messages.put(msg);
      selector.wakeup();
    }

    @Override
    public void run() {
      logger.info("Started Console Thread");
      try (var scanner = new Scanner(System.in)) {
        while (scanner.hasNextLine()) {
          var str = scanner.nextLine();
          sendCommand(str);
        }
        logger.info("Console thread stopping");
      }
    }
  }

  private static class Context {
    private final SelectionKey key;
    private final SocketChannel sc;
    private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
    private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
    private final ArrayDeque<Message> queue = new ArrayDeque<>();
    private final MessageReader messageReader = new MessageReader();
    private boolean closed = false;

    private Context(SelectionKey key) {
      this.key = key;
      this.sc = (SocketChannel) key.channel();
    }

    /**
     * Process the content of bufferIn
     *
     * The convention is that bufferIn is in write-mode before the call to process
     * and after the call
     */
    private void processIn() {
      Reader.ProcessStatus status;

      while ((status = messageReader.process(bufferIn)) == Reader.ProcessStatus.DONE) {
        var message = messageReader.get();
        messageReader.reset();
        System.out.printf("[%s]: %s%n", message.login(), message.content());
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
    private void queueMessage(Message msg) {
      queue.add(msg);
      processOut();
      updateInterestOps();
    }

    /**
     * Try to fill bufferOut from the message queue
     *
     */
    private void processOut() {
      Message msg;
      while (!queue.isEmpty() && bufferOut.remaining() >= (msg = queue.poll()).size()) {
        bufferOut.put(msg.toByteBuffer());
      }
    }

    /**
     * Update the interestOps of the key looking only at values of the boolean
     * closed and of both ByteBuffers.
     *
     * The convention is that both buffers are in write-mode before the call to
     * updateInterestOps and after the call. Also it is assumed that process has
     * been called just before updateInterestOps.
     */
    private void updateInterestOps() {
      var interestOps = 0;

      if (bufferOut.position() > 0) {
        interestOps |= SelectionKey.OP_WRITE;
      }

      if (!closed && bufferIn.hasRemaining()) {
        interestOps |= SelectionKey.OP_READ;
      }

      if (interestOps == 0) {
        silentlyClose();
        return;
      }

      key.interestOps(interestOps);
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

    public void doConnect() throws IOException {
      if (!sc.finishConnect()) {
        return; // the selector gave a bad hint
      }
      key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
    }
  }
}
