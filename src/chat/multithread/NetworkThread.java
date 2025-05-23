package chat.multithread;

import chat.Message;
import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

public class NetworkThread implements Runnable {

  private static final Logger logger = Logger.getLogger(NetworkThread.class.getName());
  private final Selector localSelector;

  public NetworkThread() throws IOException {
    localSelector = Selector.open();
  }

  public void takeCareOf(SocketChannel client, ChatonServerMultiThread server) {
    try {
      client.configureBlocking(false);
      var selectionKey = client.register(localSelector, SelectionKey.OP_READ);
      selectionKey.attach(new ChatonServerMultiThread.Context(server, selectionKey));
      localSelector.wakeup();
      System.out.println(Thread.currentThread().getName() + " is taking care of " + client.socket().getRemoteSocketAddress());
    } catch (ClosedChannelException e) {
      logger.info("closed channel cannot be registered");
    } catch (IOException e) {
      logger.warning(e.getMessage());
      silentlyClose(client);
    }
  }

  public void localBroadcast(Message message) {
    localSelector.keys()
        .stream()
        .filter(key -> key.channel() instanceof SocketChannel)
        .forEach(key -> {
          var context = (ChatonServerMultiThread.Context) key.attachment();
          context.queueMessage(message);
          localSelector.wakeup();
        });
  }

  private void treatKey(SelectionKey key) {
    System.out.println(Thread.currentThread().getName() + " key = " + key);
    try {
      if (key.isValid() && key.isWritable()) {
        ((ChatonServerMultiThread.Context) key.attachment()).doWrite();
      }
      if (key.isValid() && key.isReadable()) {
        ((ChatonServerMultiThread.Context) key.attachment()).doRead();
      }
    } catch (IOException e) {
      silentlyClose(key.channel());
    }
  }

  private void silentlyClose(Channel sc) {
    try {
      sc.close();
    } catch (IOException e) {
      // ignore exception
    }
  }

  @Override
  public void run() {
    while (!Thread.interrupted()) {
      System.out.println(Thread.currentThread().getName() + " Starting select");
      try {
        localSelector.select(this::treatKey);
      } catch (IOException e) {
        Thread.currentThread().interrupt();
        System.out.println(Thread.currentThread().getName() + " : " + e.getMessage());
      }
      System.out.println(Thread.currentThread().getName() + " Select finished");
    }
    logger.severe(Thread.currentThread().getName() + " is dead :(");
  }
}
