package chat;

import java.nio.ByteBuffer;

public interface Reader<T> {

  ProcessStatus process(ByteBuffer buffer);

  T get();

  void reset();

  enum ProcessStatus { DONE, REFILL, ERROR }

}