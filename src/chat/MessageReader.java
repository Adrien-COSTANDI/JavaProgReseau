package chat;

import java.nio.ByteBuffer;

public class MessageReader implements Reader<Message> {

  private final StringReader stringReader = new StringReader();
  private String login = null;
  private String content = null;

  @Override
  public ProcessStatus process(ByteBuffer buffer) {
    if (login == null) {
      var loginStatus = stringReader.process(buffer);

      switch (loginStatus) {
        case DONE -> {
          login = stringReader.get();
          stringReader.reset();
        }
        case REFILL -> {
          return ProcessStatus.REFILL;
        }
        case ERROR -> {
          return ProcessStatus.ERROR;
        }
      }
    }

    var contentStatus = stringReader.process(buffer);

    return switch (contentStatus) {
      case DONE -> {
        content = stringReader.get();
        stringReader.reset();
        yield ProcessStatus.DONE;
      }
      case REFILL -> ProcessStatus.REFILL;
      case ERROR -> ProcessStatus.ERROR;
    };
  }

  @Override
  public Message get() {
    if (login == null || content == null) {
      throw new IllegalStateException();
    }
    return new Message(login, content);
  }

  @Override
  public void reset() {
    login = null;
    content = null;
    stringReader.reset();
  }

}
