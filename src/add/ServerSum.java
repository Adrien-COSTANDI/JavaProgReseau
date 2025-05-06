package add;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.OP_READ;

public class ServerSum {

  public static final int PORT = 9999;
  private static final Logger logger = Logger.getLogger(ServerSum.class.getName());
  private final ServerSocketChannel serverSocketChannel;
  private final Selector selector;

  public ServerSum() throws IOException {
    this.serverSocketChannel = ServerSocketChannel.open();
    this.serverSocketChannel.bind(new InetSocketAddress(PORT));
    this.selector = Selector.open();
  }

  public void start() throws IOException {
    this.serverSocketChannel.configureBlocking(false);
    this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

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

  private void treatKey(SelectionKey key) {
    try {
      if (key.isValid() && key.isAcceptable()) {
        doAccept(key);
      }
    } catch (IOException ioe) {
      throw new UncheckedIOException("Entrance door is on fire", ioe);
    }

    try {
      if (key.isValid() && key.isWritable()) {
        doWrite(key);
      }
      if (key.isValid() && key.isReadable()) {
        doRead(key);
      }
    } catch (IOException ioe) {
      logger.info("Connection terminated with client : " + ioe.getMessage());
    }

  }

  private void doAccept(SelectionKey key) throws IOException {
    var client = serverSocketChannel.accept();

    if (client == null) {
      return;
    }
    client.configureBlocking(false);
    client.register(selector, OP_READ, new Context());
    System.out.println("new client: " + client.getRemoteAddress());
  }

  private void doRead(SelectionKey key) throws IOException {
    var clientContext = (Context) key.attachment();
    int nbRead = clientContext.readAndPutInBuffer(key);

    if (nbRead == -1) {
      silentlyClose(key);
      logger.info("client kicked");
      return;
    }

    if (clientContext.isReadyToSend()) {
      clientContext.setWritableAndPrepareResponse(key);
    }
  }

  private void doWrite(SelectionKey key) throws IOException {
    var clientContext = (Context) key.attachment();
    clientContext.writeFromBuffer(key);

    if (clientContext.isFinishedToWrite()) {
      // silentlyClose(key);
      clientContext.setReadable(key);
      logger.info("finished send");
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
}
