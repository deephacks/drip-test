import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

public class UnixServer implements AutoCloseable {

  private File file;
  private Thread thread;
  private Selector selector;
  private UnixServerSocketChannel channel;

  public UnixServer(File file) throws IOException {
    this.file = file;
    this.file.deleteOnExit();
    this.selector = NativeSelectorProvider.getInstance().openSelector();
    this.channel = UnixServerSocketChannel.open();
  }

  public static void main(String[] args) throws IOException {
    UnixServer server = new UnixServer(new File("/tmp/serv.sock"));
    server.start();
  }

  public void start() throws IOException {
    UnixSocketAddress address = new UnixSocketAddress(file);
    channel.configureBlocking(false);
    channel.socket().bind(address);
    channel.register(selector, SelectionKey.OP_ACCEPT);
    thread = new Thread(() -> {
      try {
        while (selector.select() > 0) {
          Set<SelectionKey> keys = selector.selectedKeys();
          Iterator<SelectionKey> iterator = keys.iterator();
          while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            if (key.isAcceptable()) {
              handleAccept(key);
            } else if (key.isReadable()) {
              handleRead(key);
            }
            iterator.remove();
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        if (selector != null) {
          try {
            selector.close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
    });
    thread.start();
  }

  public void close() {
    thread.interrupt();
  }

  private void handleAccept(SelectionKey key) {
    try {
      UnixSocketChannel client = channel.accept();
      client.configureBlocking(false);
      client.register(selector, SelectionKey.OP_READ);
    } catch (IOException ex) {
      key.cancel();
    }
  }

  private void handleRead(SelectionKey key) {
    try {
      UnixSocketChannel channel = (UnixSocketChannel) key.channel();
      ByteBuffer buf = ByteBuffer.allocate(1024);
      int n = channel.read(buf);
      if (n > 0) {
        buf.flip();
        byte[] bytes = new byte[n];
        buf.get(bytes);
        String echo = new String(bytes, StandardCharsets.UTF_8);
        writeResponse(key, channel, echo + " echoes");
      } else if (n < 0) {
        key.cancel();
      }
    } catch (IOException ex) {
      // log.error("Error reading key.", ex);
      key.cancel();
    }
  }

  private void writeResponse(SelectionKey key, UnixSocketChannel channel, String str) {
    try {
      final byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
      ByteBuffer bb = ByteBuffer.wrap(bytes);
      int n = 0;
      while (n != bytes.length) {
        n = channel.write(bb);
        if (n != bytes.length) {
          // FIX: register OP_WRITE and use select loop instead
          LockSupport.parkNanos(10);
          bb.flip();
        }
        if (n == -1) {
          close();
        }
      }
    } catch (IOException e) {
      close(key);
    }
  }

  private void close(SelectionKey key) {
    try {
      if (key != null) {
        key.cancel();
        final UnixSocketChannel channel = (UnixSocketChannel) key.channel();
        channel.shutdownInput();
        channel.shutdownOutput();
        channel.close();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
