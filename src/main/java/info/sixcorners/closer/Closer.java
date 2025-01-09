package info.sixcorners.closer;

import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.util.stream.Stream;
import javax.imageio.stream.ImageInputStream;
import lombok.Lombok;
import lombok.val;

/**
 * Collects and closes resources in the reverse order
 *
 * <p>Inspired by {@link Stream#close}. Not thread-safe. Also contains static utility methods for
 * closing resources that are thread-safe.
 */
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

  /** Calls {@link #close} */
  @Override
  public void run() {
    close();
  }

  /** Handle to one {@link AutoCloseable} registered in a {@link Closer} */
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

    /**
     * Closes the associated {@link AutoCloseable}
     *
     * <p>Repeated calls will do nothing.
     */
    @Override
    public void close() {
      compact(this, 1);
      justClose();
    }

    /** Calls {@link #close} */
    @Override
    public void run() {
      close();
    }
  }

  /**
   * Remove closed {@link Handle}s
   *
   * @param startWith {@link Handle} to start with
   * @param modLimit number of modifications to make
   */
  public static void compact(Handle startWith, int modLimit) {
    Handle prev = startWith;
    for (Handle handle = startWith.next; handle != null; handle = handle.next) {
      if (handle.closeable == null) continue;
      if (prev.next != handle) prev.next = handle;
      if (modLimit-- <= 0) break;
      prev = handle;
    }
  }

  /**
   * Remove closed {@link Handle}s
   *
   * @param startWith {@link Handle} to start with
   */
  public static void compact(Handle startWith) {
    compact(startWith, Integer.MAX_VALUE);
  }

  /** Remove closed {@link Handle}s */
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
