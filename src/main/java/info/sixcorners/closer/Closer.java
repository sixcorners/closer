package info.sixcorners.closer;

import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.util.stream.Stream;
import javax.imageio.stream.ImageInputStream;
import lombok.Lombok;
import lombok.val;

public final class Closer implements Closeable, Runnable {
  private Handle head;

  /** Registers an {@link AutoCloseable} to be closed in reverse order */
  public Handle onClose(AutoCloseable closeable) {
    val newNode = new Handle(closeable);
    newNode.next = head;
    return head = newNode;
  }

  /** Closes registered {@link AutoCloseable}s in reverse order */
  @Override
  public void close() {
    Throwable t = null;
    Handle handle = head;
    head = null;
    while (handle != null) {
      try {
        handle.justClose();
      } catch (Throwable e) {
        if (t == null) {
          t = e;
        } else if (t != e) {
          t.addSuppressed(e);
        }
      }
      val oldHandle = handle;
      handle = handle.next;
      oldHandle.next = null; // things might hold onto the Handle and prevent gc
    }
    if (t != null) throw sneakyThrow(t);
  }

  @Override
  public void run() {
    close();
  }

  public static final class Handle implements Closeable, Runnable {
    private Handle next;
    private AutoCloseable closeable;

    /**
     * Can be used as a wrapper that only performs a close once
     *
     * <p>Registered {@link Cleaner.Cleanable}s have this same effect.
     *
     * <p>Useful for {@link ImageInputStream}s.
     */
    public Handle(AutoCloseable closeable) {
      this.closeable = closeable;
    }

    private void justClose() {
      val closeable = this.closeable;
      if (closeable != null) {
        this.closeable = null;
        Closer.close(closeable);
      }
    }

    @Override
    public void close() {
      compact(this, 1);
      justClose();
    }

    @Override
    public void run() {
      close();
    }
  }

  public static void compact(Handle handle, int limit) {
    Handle prev = handle;
    for (handle = handle.next; handle != null; handle = handle.next) {
      if (handle.closeable == null) continue;
      if (prev.next != handle) prev.next = handle;
      if (limit-- <= 0) break;
      prev = handle;
    }
  }

  public static void compact(Handle handle) {
    compact(handle, Integer.MAX_VALUE);
  }

  /** Remove closeables that have been run from the list */
  public void compact() {
    if (head != null) compact(head);
  }

  int size() {
    int i = 0;
    for (Handle handle = head; handle != null; handle = handle.next) i++;
    return i;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T sneakyThrow0(Throwable t) throws T {
    throw (T) t;
  }

  /**
   * Throws as an unchecked exception.
   *
   * <p>Works like how Kotlin throws checked exceptions.
   *
   * @param t the throwable to rethrow
   * @return never returns; always throws
   * @see Lombok#sneakyThrow
   */
  public static RuntimeException sneakyThrow(Throwable t) {
    return sneakyThrow0(t);
  }

  /** Closes with throwing */
  public static void close(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      throw sneakyThrow(e);
    }
  }

  /** Closes without throwing */
  public static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Throwable ignored) {
    }
  }

  /**
   * Converts an {@link AutoCloseable} to a {@link Runnable}
   *
   * <p>Useful for {@link Cleaner#register} and {@link Stream#onClose}.
   */
  public static Runnable toRunnable(AutoCloseable closeable) {
    return () -> close(closeable);
  }
}
