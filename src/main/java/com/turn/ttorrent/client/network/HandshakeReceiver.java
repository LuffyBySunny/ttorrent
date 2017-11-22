package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeReceiver.class);

  private final PeersStorageProvider peersStorageProvider;
  private final TorrentsStorageProvider torrentsStorageProvider;
  private final String myHostAddress;
  private final int myPort;
  private final boolean myOnlyRead;
  private ByteBuffer messageBytes;
  private int pstrLength;
  private final PeerActivityListener myPeerActivityListener;

  public HandshakeReceiver(PeersStorageProvider peersStorageProvider,
                           TorrentsStorageProvider torrentsStorageProvider,
                           PeerActivityListener myPeerActivityListener,
                           String hostAddress,
                           int port,
                           boolean onlyRead) {
    this.peersStorageProvider = peersStorageProvider;
    this.torrentsStorageProvider = torrentsStorageProvider;
    this.myPeerActivityListener = myPeerActivityListener;
    myHostAddress = hostAddress;
    myPort = port;
    this.pstrLength = -1;
    this.myOnlyRead = onlyRead;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    if (pstrLength == -1) {
      ByteBuffer len = ByteBuffer.allocate(1);
      int readBytes = -1;
      try {
        readBytes = socketChannel.read(len);
      } catch (IOException ignored) {
      }
      if (readBytes == -1) {
        return new ShutdownProcessor();
      }
      if (readBytes == 0) {
        return this;
      }
      len.rewind();
      byte pstrLen = len.get();
      this.pstrLength = pstrLen;
      messageBytes = ByteBuffer.allocate(this.pstrLength + Handshake.BASE_HANDSHAKE_LENGTH);
      messageBytes.put(pstrLen);
    }
    int readBytes = -1;
    try {
      readBytes = socketChannel.read(messageBytes);
    } catch (IOException e) {
      e.printStackTrace();
    }
    if (readBytes == -1) {
      return new ShutdownProcessor();
    }
    if (messageBytes.remaining() != 0) {
      return this;
    }
    Handshake hs = parseHandshake(socketChannel.toString());

    if (hs == null) {
      return new ShutdownProcessor();
    }

    SharedTorrent torrent = torrentsStorageProvider.getTorrentsStorage().getTorrent(hs.getHexInfoHash());

    if (torrent == null) {
      logger.debug("peer {} tries to download unknown torrent {}",
              Arrays.toString(hs.getPeerId()),
              hs.getHexInfoHash());
      return new ShutdownProcessor();
    }

    logger.debug("got handshake {} from {}", Arrays.toString(messageBytes.array()), socketChannel);

    if (!myOnlyRead) {
      logger.debug("send handshake to {}", socketChannel);
      ConnectionUtils.sendHandshake(socketChannel, hs.getInfoHash(), peersStorageProvider.getPeersStorage().getSelf().getPeerIdArray());
    }

    final String peerId = new String(hs.getPeerId(), Torrent.BYTE_ENCODING);
    SharingPeer sharingPeer = new SharingPeer(myHostAddress, myPort, ByteBuffer.wrap(hs.getPeerId()), torrent);
    PeerUID peerUID = new PeerUID(peerId, hs.getHexInfoHash());
    SharingPeer old = peersStorageProvider.getPeersStorage().putIfAbsent(peerUID, sharingPeer);
    if (old != null) {
      logger.debug("old peer is {}", old);
      logger.debug("Already connected to {}, close current connection...", sharingPeer);
      return new ShutdownAndRemovePeerProcessor(peerUID, peersStorageProvider);
    }

    registerListenersAndBindChannel(sharingPeer, socketChannel, torrent);

    return new WorkingReceiver(peerUID, peersStorageProvider, torrentsStorageProvider);
  }

  private Handshake parseHandshake(String socketChannelForLog) throws IOException {
    try {
      messageBytes.rewind();
      return Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.debug("incorrect handshake message from " + socketChannelForLog, e);
    }
    return null;
  }

  private void registerListenersAndBindChannel(SharingPeer sharingPeer, ByteChannel socketChannel, SharedTorrent torrent) throws IOException {
    sharingPeer.setTorrentHash(torrent.getHexInfoHash());

    sharingPeer.register(torrent);
    sharingPeer.register(myPeerActivityListener);
    sharingPeer.bind(socketChannel);
  }
}
