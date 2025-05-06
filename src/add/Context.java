package add;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class Context {

  private final ByteBuffer bufferSend = ByteBuffer.allocate(Integer.BYTES);
  private final ByteBuffer bufferReceive = ByteBuffer.allocate(2 * Integer.BYTES);

  public int readAndPutInBuffer(SelectionKey key) throws IOException {
    var socket = (SocketChannel) key.channel();
    return socket.read(bufferReceive);
  }

  public int writeFromBuffer(SelectionKey key) throws IOException {
    var socket = (SocketChannel) key.channel();
    return socket.write(bufferSend);
  }

  public boolean isReadyToSend() {
    return !bufferReceive.hasRemaining();
  }

  public void setWritableAndPrepareResponse(SelectionKey key) {
    bufferReceive.flip();
    var a = bufferReceive.getInt();
    var b = bufferReceive.getInt();

    bufferSend.putInt(a + b).flip();
    key.interestOps(SelectionKey.OP_WRITE);
  }

  public boolean isFinishedToWrite() {
    return !bufferSend.hasRemaining();
  }

  public void setReadable(SelectionKey key) {
    bufferReceive.clear();
    bufferSend.clear();
    key.interestOps(SelectionKey.OP_READ);
  }
}
