package chat;

import java.nio.ByteBuffer;

public interface Writter<T> {

  ProcessStatus process(ByteBuffer bb);

  void put(T value);

  void reset();

  enum ProcessStatus { DONE, REFILL, ERROR }

}
