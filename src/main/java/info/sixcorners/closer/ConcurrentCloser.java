package info.sixcorners.closer;

import java.io.Closeable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import javax.imageio.stream.ImageInputStream;
import lombok.val;

/**
 * Thread-safe version of {@link Closer}
 *
 * <p>Only works in Java 9+.
 *
 * <p>One way to use this could be to put your stuff into a normal {@link Closer} then add it to
 * this instead of calling {@link #onClose} repeatedly. That does change the order slightly though.
 */
@SuppressWarnings({"FieldMayBeFinal", "unused"})
public final class ConcurrentCloser implements Closeable, Runnable {
  private static final Handle CLOSED = new Handle(null);
  private static final VarHandle HEAD;

  static {
    val mh = MethodHandles.lookup();
    try {
      HEAD = mh.findVarHandle(ConcurrentCloser.class, "head", Handle.class);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("findVarHandle", e);
    }
  }

  private Handle head;

  /**
   * Registers an {@link AutoCloseable} to be closed in reverse order
   *
   * <p>Does not work while this {@link ConcurrentCloser} is closed.
   */
  public Handle onClose(AutoCloseable closeable) {
    val newNode = new Handle(closeable);
    Handle oldNode = head;
    do {
      if (oldNode == CLOSED) throw new IllegalStateException("closed");
      newNode.next = oldNode;
      oldNode = (Handle) HEAD.compareAndExchangeRelease(this, oldNode, newNode);
    } while (newNode.next != oldNode);
    return newNode;
  }

  private void close(Handle newHead) {
    Throwable t = null;
    Handle handle = (Handle) HEAD.getAndSetAcquire(this, newHead);
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
    if (t != null) throw Closer.sneakyThrow(t);
  }

  /** Closes registered {@link AutoCloseable}s in reverse order */
  @Override
  public void close() {
    close(null);
  }

  /**
   * Closes registered {@link AutoCloseable}s in reverse order and prevents successful calls to
   * {@link #onClose}
   */
  public void closeForGood() {
    close(CLOSED);
  }

  /** Calls {@link #close} */
  @Override
  public void run() {
    close();
  }

  /** Handle to one {@link AutoCloseable} registered in a {@link ConcurrentCloser} */
  public static final class Handle implements Closeable, Runnable {
    private static final VarHandle CLOSEABLE;
    private Handle next;
    private AutoCloseable closeable;

    static {
      val mh = MethodHandles.lookup();
      try {
        CLOSEABLE = mh.findVarHandle(Handle.class, "closeable", AutoCloseable.class);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("findVarHandle", e);
      }
    }

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
      val closeable = (AutoCloseable) CLOSEABLE.getAndSetRelease(this, null);
      if (closeable != null) Closer.close(closeable);
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
}
