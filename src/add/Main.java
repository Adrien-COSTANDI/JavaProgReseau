import add.Application;

void main() {

  try {
    var application = new Application();
    application.start();
  } catch (IOException e) {
    throw new RuntimeException(e);
  }
}
