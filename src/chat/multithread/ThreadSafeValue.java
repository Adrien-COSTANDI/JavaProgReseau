package chat.multithread;

import java.util.Objects;

public class ThreadSafeValue<T> {

    private final Object lock = new Object();
    private T value = null;

    public void put(T value) throws InterruptedException {
      Objects.requireNonNull(value);
      synchronized (lock) {
        if (!isEmpty()) {
          // lock.wait();
          throw new IllegalStateException("value is already set");
        }
        this.value = value;
        lock.notify();
      }
    }

    public boolean isEmpty() {
      synchronized (lock) {
        return value == null;
      }
    }

    public T take() throws InterruptedException {
      synchronized (lock) {
        while (isEmpty()) {
          lock.wait();
        }
        return value;
      }
    }
  }