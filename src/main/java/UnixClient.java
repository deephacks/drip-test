import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

public class UnixClient {

  public static void main(String[] args) throws IOException {

    UnixClient client = new UnixClient(new File("/tmp/serv.sock"));
    String response = client.write("request");
    System.out.println(response);
  }

  private Selector selector;
  private File file;

  public UnixClient(File file) throws IOException {
    int retries = 0;
    while (!file.exists()) {
      try {
        TimeUnit.MILLISECONDS.sleep(500L);
      } catch (InterruptedException e) {
        return;
      }
      retries++;
      if (retries > 10) {
        throw new IOException(
            String.format("File %s does not exist after retry", file.getAbsolutePath()));
      }
    }
    this.file = file;
    this.selector = NativeSelectorProvider.getInstance().openSelector();
  }

  public String write(String cmd) throws IOException {
    UnixSocketAddress address = new UnixSocketAddress(file);
    UnixSocketChannel channel = UnixSocketChannel.open(address);
    PrintWriter w = new PrintWriter(Channels.newOutputStream(channel));
    w.print(cmd);
    w.flush();

    try {
      channel.configureBlocking(false);
      channel.register(selector, SelectionKey.OP_READ);
      while (selector.select() > 0) {
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = keys.iterator();
        while (iterator.hasNext()) {
          SelectionKey key = iterator.next();
          if (key.isReadable()) {
            ByteBuffer buf = ByteBuffer.allocate(1024);
            int n = channel.read(buf);
            if (n == -1) {
              return "ERR";
            }
            buf.flip();
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            CharBuffer charBuffer = decoder.decode(buf);
            return charBuffer.toString();
          }
          iterator.remove();
        }
      }
      return "nothing";
    } finally {
      selector.close();
    }
  }
}
