package info.sixcorners.closer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CloserTest {
  @Mock AutoCloseable closeable;
  @Mock AutoCloseable closeableB;
  Closer closer = new Closer();

  @Test
  void onClose() throws Exception {
    closer.onClose(closeable);
    closer.close();
    closer.close();
    verify(closeable).close();
  }

  @Test
  void two_throwing() throws Exception {
    doThrow(new Exception()).when(closeable).close();
    doThrow(new Exception()).when(closeableB).close();
    closer.onClose(closeable);
    closer.onClose(closeableB);
    val e = assertThrowsExactly(Exception.class, () -> closer.close());
    assertInstanceOf(Exception.class, e.getSuppressed()[0]);
    assertEquals(1, e.getSuppressed().length);
    val inOrder = inOrder(closeable, closeableB);
    inOrder.verify(closeableB).close();
    inOrder.verify(closeable).close();
  }

  @Test
  void two_throwing_same_exception() throws Exception {
    doThrow(new Exception()).when(closeable).close();
    closer.onClose(closeable);
    closer.onClose(closeable);
    val e = assertThrowsExactly(Exception.class, () -> closer.close());
    assertEquals(0, e.getSuppressed().length);
    verify(closeable, times(2)).close();
  }

  @Test
  void close_handle() throws Exception {
    closer.onClose(closeable).close();
    closer.close();
    closer.close();
    verify(closeable).close();
  }

  @Test
  void closeQuietly() throws Exception {
    Closer.closeQuietly(closeable);
    verify(closeable).close();
  }

  @Test
  void closeQuietly_throwing() throws Exception {
    doThrow(new Exception()).when(closeable).close();
    Closer.closeQuietly(closeable);
    verify(closeable).close();
  }

  @Test
  void close() throws Exception {
    Closer.close(closeable);
    verify(closeable).close();
  }

  @Test
  void close_throwing() throws Exception {
    doThrow(new Exception()).when(closeable).close();
    assertThrowsExactly(Exception.class, () -> Closer.close(closeable));
    verify(closeable).close();
  }

  @Test
  void sneakyThrow() {
    assertThrowsExactly(Exception.class, () -> Closer.sneakyThrow(new Exception()));
  }

  @Test
  void toRunnable() throws Exception {
    Closer.toRunnable(() -> closeable.close()).run();
    verify(closeable).close();
  }

  @Test
  void toRunnable_throwing() throws Exception {
    doThrow(new Exception()).when(closeable).close();
    assertThrowsExactly(Exception.class, () -> Closer.toRunnable(() -> closeable.close()).run());
    verify(closeable).close();
  }

  @Test
  void compact() throws Exception {
    closer.compact();
    closer.onClose(closeable);
    closer.onClose(closeable);
    val c = closer.onClose(closeable);
    val d = closer.onClose(closeable);
    closer.onClose(closeable);
    closer.onClose(closeable);
    assertEquals(6, closer.size());
    c.close();
    d.close();
    assertEquals(5, closer.size());
    closer.compact();
    assertEquals(4, closer.size());
    verify(closeable, times(2)).close();
    closer.close();
    verify(closeable, times(6)).close();
  }
}
