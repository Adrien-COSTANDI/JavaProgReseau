package chat;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public record Message(String login, String content) {
  private static final Charset UTF8 = StandardCharsets.UTF_8;

  public int size() {
    var bytesLogin = UTF8.encode(login);
    var bytesContent = UTF8.encode(content);

    int sizeLogin = bytesLogin.remaining();
    int sizeContent = bytesContent.remaining();

    return 2 * Integer.BYTES + sizeLogin + sizeContent;
  }

  public ByteBuffer toByteBuffer() {
    var bytesLogin = UTF8.encode(login);
    var bytesContent = UTF8.encode(content);

    int sizeLogin = bytesLogin.remaining();
    int sizeContent = bytesContent.remaining();

    var buffer = ByteBuffer.allocate(2 * Integer.BYTES + sizeLogin + sizeContent);

    return buffer.putInt(sizeLogin)
        .put(bytesLogin)
        .putInt(sizeContent)
        .put(bytesContent)
        .flip();
  }
}
