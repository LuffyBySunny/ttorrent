package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.CachedPeersStorageFactory;
import com.turn.ttorrent.common.CachedTorrentsStorageFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class ConnectionReceiverTest {

  private ConnectionReceiver myConnectionReceiver;
  private ChannelListener channelListener;

  @BeforeMethod
  public void setUp() throws Exception {
    ChannelListenerFactory channelListenerFactory = new ChannelListenerFactory() {
      @Override
      public ChannelListener newChannelListener() {
        return channelListener;
      }
    };
    myConnectionReceiver = new ConnectionReceiver(InetAddress.getByName("127.0.0.1"),
            new CachedPeersStorageFactory(),
            new CachedTorrentsStorageFactory(),
            channelListenerFactory);
  }

  @Test
  public void canAcceptAndReadData() throws IOException, InterruptedException {
    final AtomicInteger acceptCount = new AtomicInteger();
    final AtomicInteger readCount = new AtomicInteger();
    final AtomicInteger lastReadBytesCount = new AtomicInteger();
    final ByteBuffer byteBuffer = ByteBuffer.allocate(10);

    final Semaphore semaphore = new Semaphore(0);

    this.channelListener = new ChannelListener() {
      @Override
      public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
        readCount.incrementAndGet();
        lastReadBytesCount.set(socketChannel.read(byteBuffer));
        if (lastReadBytesCount.get() == -1) {
          socketChannel.close();
        }
        semaphore.release();
      }

      @Override
      public void onConnectionAccept(SocketChannel socketChannel) throws IOException {
        acceptCount.incrementAndGet();
        semaphore.release();
      }
    };

    Future<?> future = Executors.newSingleThreadExecutor().submit(myConnectionReceiver);

    assertEquals(acceptCount.get(), 0);
    assertEquals(readCount.get(), 0);

    Socket socket = new Socket("127.0.0.1", ConnectionReceiver.PORT_RANGE_START);

    tryAcquireOrFail(semaphore);//wait until connection is accepted

    assertTrue(socket.isConnected());
    assertEquals(acceptCount.get(), 1);
    assertEquals(readCount.get(), 0);

    Socket socketSecond = new Socket("127.0.0.1", ConnectionReceiver.PORT_RANGE_START);

    tryAcquireOrFail(semaphore);//wait until connection is accepted

    assertTrue(socketSecond.isConnected());
    assertEquals(acceptCount.get(), 2);
    assertEquals(readCount.get(), 0);
    socketSecond.close();
    tryAcquireOrFail(semaphore);//wait read that connection is closed
    assertEquals(readCount.get(), 1);
    assertEquals(acceptCount.get(), 2);
    assertEquals(lastReadBytesCount.get(), -1);
    byteBuffer.rewind();
    assertEquals(byteBuffer.get(), 0);
    byteBuffer.rewind();
    String writeStr = "abc";
    OutputStream outputStream = socket.getOutputStream();
    outputStream.write(writeStr.getBytes());
    tryAcquireOrFail(semaphore);//wait until read bytes
    assertEquals(readCount.get(), 2);
    assertEquals(lastReadBytesCount.get(), 3);
    byte[] expected = new byte[byteBuffer.capacity()];
    System.arraycopy(writeStr.getBytes(), 0, expected, 0, writeStr.length());
    assertEquals(byteBuffer.array(), expected);
    outputStream.close();
    socket.close();
    tryAcquireOrFail(semaphore);//wait read that connection is closed
    assertEquals(readCount.get(), 3);
    future.cancel(true);
  }

  private void tryAcquireOrFail(Semaphore semaphore) throws InterruptedException {
    if (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
      fail("don't get signal from connection receiver that connection selected");
    }
  }
}
