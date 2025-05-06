package add;

import java.io.IOException;

public class Application {

  private final ServerSum serverSum = new ServerSum();

  public Application() throws IOException { }

  public void start() throws IOException {
    serverSum.start();
  }

}
