package chat;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class StringReader implements Reader<String> {

  public static final int MAX_SIZE = 1024;
  private static final Charset UTF8 = StandardCharsets.UTF_8;
  private final ByteBuffer internalBuffer = ByteBuffer.allocate(MAX_SIZE); // write mode
  private final IntReader intReader = new IntReader();
  private String value;
  private State state = State.WAITING;
  private Integer size = null;

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    if (state == State.DONE || state == State.ERROR) {
      throw new IllegalStateException();
    }

    if (size == null) {
      var intStatus = intReader.process(buffer);
      switch (intStatus) {
        case DONE -> {
          size = intReader.get();
          if (size < 0) {
            state = State.ERROR;
            return ProcessStatus.ERROR;
          }
          if (size > MAX_SIZE) {
            state = State.ERROR;
            return ProcessStatus.ERROR;
          }
          internalBuffer.limit(size);
        }
        case ERROR -> {
          state = State.ERROR;
          return ProcessStatus.ERROR;
        }
        case REFILL -> {
          return ProcessStatus.REFILL;
        }
      }
    }

    buffer.flip(); // on va lire son contenu

    try {
      if (buffer.remaining() <= internalBuffer.remaining()) { // on ne veut pas lire plus que le texte recherché
        internalBuffer.put(buffer);
      } else {
        var oldLimit = buffer.limit();
        buffer.limit(internalBuffer.remaining());
        internalBuffer.put(buffer);
        buffer.limit(oldLimit);
      }
    } finally {
      buffer.compact();
    }
    if (internalBuffer.hasRemaining()) {
      return ProcessStatus.REFILL;
    }
    state = State.DONE;
    internalBuffer.flip();
    value = UTF8.decode(internalBuffer).toString();
    return ProcessStatus.DONE;
  }

  @Override
  public String get() {
    if (state != State.DONE) {
      throw new IllegalStateException();
    }
    return value;
  }

  @Override
  public void reset() {
    intReader.reset();
    size = null;
    internalBuffer.clear();
    state = State.WAITING;
  }

  private enum State {
    DONE, WAITING, ERROR
  }
}
