/**
 * TODO: timeouts
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private ConcurrentLinkedQueue<Msg> msgsForThread;
	private static RTPSocket readerSocket; //will be created by the server thread when accept is called
	private static Object lock = new Object();
	private static final int TIMEOUT = 50; //50 ms receive timeout
	private static int maxWinSize; //the maximum receive window size in packets
	private static final int MAX_PACKET_SIZE = 1000; //bytes


	public ServerRTPSocket(int UDPport, int maxWinSize) {
		DatagramSocket socket = null;
		this.maxWinSize = maxWinSize;
		msgsForThread = new ConcurrentLinkedQueue<>();
		try {
			socket = new DatagramSocket(UDPport);
		} catch (SocketException e) {
			System.out.println("unable to create datagram socket");
			System.exit(-1);
		}

		(new ServerThread(socket, msgsForThread)).start();
	}

	/**
	 * blocking
	 * returns an object from which the application can read from the stream
	 */
	public RTPSocket accept() {
		Msg acceptMsg = new Msg("accept");
		msgsForThread.add(acceptMsg);
		synchronized(lock) {
			while (null == readerSocket) {
				try {
					lock.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		RTPSocket toReturn = readerSocket;
		readerSocket = null;
		return toReturn;
	}


	private static class ServerThread extends Thread {
		private DatagramSocket socket;
		private ConcurrentLinkedQueue<Msg> msgs;
		private static enum AcceptStatus {
			AVAILABLE_FOR_CONNECTION,
			RECEIVED_CONNECTION_REQUEST,
			RESPONDED_TO_CONNECTION_REQUEST,
			RECEIVED_ACK_TO_RESPONSE
		}
		private AcceptStatus acceptStatus;
		private InetAddress connReqAddr; //address of client requesting a connection
		private int connReqPort; //port of client requesting a connection
		private List<RTPSocket> rtpSockets;
		private int  peerWinSize; //the window size of the peer who we are currently setting up a connection to
		private static final int ACK_TIMEOUT = 500; //how long to wait for ACK before resending in ms
		

		public ServerThread(DatagramSocket socket, ConcurrentLinkedQueue<Msg> msgs) {
			this.socket = socket;
			this.msgs = msgs;

			acceptStatus = null;
			rtpSockets = new ArrayList<>();
		}

		public void run() {
			System.out.println("server thread started");
			//where the work of the thread gets done
			
			while (true) {
				boolean receivedSomething = true;
				if (!msgs.isEmpty()) {
					Msg msg = msgs.poll();
					//since accept() is blocking, there will be only one accept msg at once
					if ("accept".equals(msg.type)) {
						acceptStatus = AcceptStatus.AVAILABLE_FOR_CONNECTION;
					}
				}
				

				byte[] rcvdBytes = new byte[1000];
				DatagramPacket rcvPkt = new DatagramPacket(rcvdBytes, rcvdBytes.length);
				try {
					socket.setSoTimeout(TIMEOUT);
					socket.receive(rcvPkt);
				} catch (SocketTimeoutException e) {
					//socket timed out
					receivedSomething = false;
				} catch (IOException e) {
					System.out.println("issue receiving on socket" + socket);
				}

				if (receivedSomething) {
					Packet received = new Packet(rcvdBytes);
					System.out.println("got a packet! ");

					if (received.checksumMatch) { //only do stuff with packet if it isnt corrupt
						//check if the packet is a connection initiation packet from the client( the first packet of a 3-wway handshake
						if (received.isConnectionInit() && AcceptStatus.AVAILABLE_FOR_CONNECTION == acceptStatus) { //if packet is a connection initilaization packet and seerver app has called accept()
							acceptStatus = AcceptStatus.RECEIVED_CONNECTION_REQUEST;
							connReqAddr = rcvPkt.getAddress();
							connReqPort = rcvPkt.getPort();
							peerWinSize = 500; //FIX

						} else if (received.isConnectionInitConfirmAck() //received last part of 3-way handshake
									&& AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST == acceptStatus
									&& rcvPkt.getAddress().equals(connReqAddr) //make sure that the last part of the handshake came from the client you were expecting it to come from
									&& rcvPkt.getPort() == connReqPort) {
							acceptStatus = AcceptStatus.RECEIVED_ACK_TO_RESPONSE;

						} else if (received.isData()) {
							RTPSocket rtpSocket = rtpSockets.get(rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())));

							if (rtpSocket.bufferList.contains(received)) {
								ack(received, rtpSocket, rcvPkt);
							} else if (received.getPacketSize() <= rtpSocket.rcvWinSize) { //check if packet fits in buffer (rceive window) of the socket on this computer
									rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
									rtpSocket.rcvWinSize -= received.getPacketSize(); //decrease the window size by the size of the packet that was just put in it
									rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can
									ack(received, rtpSocket, rcvPkt);
							}

							rtpSocket.peerWinSize = received.winSize;
						} else if (received.isAck()) {
							RTPSocket rtpSocket = rtpSockets.get(rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())));

							System.out.println("got an ack: " + received.seqNum);
							//stop caring about packets you've sent once they are ACKed
							Iterator<Packet> pListIter = rtpSocket.unAckedPackets.iterator();
							while (pListIter.hasNext()) {
								Packet packet = pListIter.next();
								System.out.println("packet seqNum: " +  packet.seqNum);
								System.out.println("ack seqNum: " + received.seqNum);
								if (packet.seqNum == received.seqNum) {
									pListIter.remove();
									rtpSocket.unAckedPktToTimeSentMap.remove(packet);
									rtpSocket.numBytesUnacked -= packet.getDataSize();
									System.out.println("# of unacked packets decreased to: " + rtpSocket.unAckedPackets.size());
									break;
								}
							}

							int seqNum = received.seqNum;
							if (seqNum > rtpSocket.highestSeqNumAcked) {
								rtpSocket.highestSeqNumAcked = seqNum;
							}

						}

						if (acceptStatus == AcceptStatus.RECEIVED_CONNECTION_REQUEST) {
							System.out.println("received connection request");
							System.out.println("responding...");

							Packet connReqRespPkt = new Packet(new byte[0], Packet.CONNECTION_INIT_CONFIRM, 0, maxWinSize);
							byte[] connReqRespBytes = connReqRespPkt.getBytes();


							DatagramPacket connReqRespMsg = new DatagramPacket(connReqRespBytes, connReqRespBytes.length, connReqAddr, connReqPort);
							try {
								socket.send(connReqRespMsg);
							} catch (IOException e) {
								System.out.println("issue sending connReqRespMsg");
								System.exit(-1);
							}
							acceptStatus = AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST;

						} else if (acceptStatus == AcceptStatus.RECEIVED_ACK_TO_RESPONSE) {
							System.out.println("3-way handshake complete for client at" + connReqAddr + ":" + connReqPort);
							//at this point, the 3-way handshake is complete and the server must set up resources to receive data from the client
							acceptStatus = null;
							System.out.println("creating rtpsocket with maxwinsize " + maxWinSize);
							RTPSocket socketForClient = new RTPSocket(connReqAddr, connReqPort, new ConcurrentLinkedDeque<>(), new ConcurrentLinkedDeque<>(), maxWinSize, peerWinSize);
							rtpSockets.add(socketForClient);
							synchronized(lock) {
								readerSocket = socketForClient;
								lock.notify();
							}
						}
					}
				}


				//for every RTPSocket, send stuff the socket wants to send (to clients)
				for (RTPSocket rtpSocket: rtpSockets) {

					//resend unacked packets which have timed out
					for (Packet packet: rtpSocket.unAckedPktToTimeSentMap.keySet()) {
						long timeSent = rtpSocket.unAckedPktToTimeSentMap.get(packet);
						if (System.currentTimeMillis() - timeSent > ACK_TIMEOUT) { //ack not received in time, so resend
							DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
							try {
								socket.send(sndPkt);
							} catch (IOException e) {
								System.out.println("issue sending packet");
							}
							rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
						}
					}

						
					//fix combine multiple things in queue
					//send data
					Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
					long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
					int winSpaceLeft = rtpSocket.peerWinSize - rtpSocket.numBytesUnacked - Packet.getHeaderSize() * rtpSocket.unAckedPackets.size();

					if (rtpSocket.dataOutQueue.peek() != null
							&& winSpaceLeft > Packet.getHeaderSize()) { //if use while, not if could have some issues with dominating the connection if the queue is constantly populated
						if (winSpaceLeft > 0) {
							System.out.println("win space left: " + winSpaceLeft);
						}

						byte[] removedBytes = rtpSocket.dataOutQueue.poll();
						int maxDataSize = MAX_PACKET_SIZE - Packet.getHeaderSize(); //the most data we can send without going over 1000 byte packet size limit
						int winSpaceLeftForData = winSpaceLeft - Packet.getHeaderSize();
						int amtOfDataToPutInPacket = winSpaceLeftForData > maxDataSize ? maxDataSize : winSpaceLeftForData;
						amtOfDataToPutInPacket = amtOfDataToPutInPacket > removedBytes.length ? removedBytes.length : amtOfDataToPutInPacket;

						byte[] pktData = new byte[amtOfDataToPutInPacket];
						System.arraycopy(removedBytes, 0, pktData, 0, amtOfDataToPutInPacket);

						//put the data in a packet and send it
						Packet packet = new Packet(pktData, Packet.DATA, rtpSocket.seqNum++, rtpSocket.maxRcvWinSize); //the rtp packet

						DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
						try {
							socket.send(sndPkt);
						} catch (IOException e) {
							System.out.println("issue sending packet");
						}
						rtpSocket.unAckedPackets.add(packet);
						rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
						rtpSocket.totalBytesSent += amtOfDataToPutInPacket;
						rtpSocket.numBytesUnacked += amtOfDataToPutInPacket;
						//System.out.println("# of unacked bytes increased to: " + rtpSocket.numBytesUnacked);
						


						//put the data you wont be sendign at front of queue if need be
						int numBytesUnsent = removedBytes.length - amtOfDataToPutInPacket;
						if (numBytesUnsent > 0) {
							byte[] unsentBytes = new byte[numBytesUnsent];
							System.arraycopy(removedBytes, amtOfDataToPutInPacket, unsentBytes, 0, numBytesUnsent);
							rtpSocket.dataOutQueue.offerFirst(unsentBytes); //put unsent bytes back at the beginning of the queue
						}
				
					}
				}
			}
		}

		private void ack(Packet toAck, RTPSocket rtpSocket, DatagramPacket rcvPkt) {
			// ack the received packet even if we have it already
			System.out.println("received: " + toAck.seqNum + ", ACKing");
			Packet ackPack = new Packet(new byte[0], Packet.ACK, toAck.seqNum, rtpSocket.maxRcvWinSize);

			
			try {
				socket.send(new DatagramPacket(ackPack.getBytes(), ackPack.getBytes().length, rcvPkt.getAddress(), rcvPkt.getPort()));
			} catch (IOException e) {
				System.out.println("issue sending ACK");
			}
		}

	}

	private class Msg {
		private String type;
		public Msg(String type) {
			this.type = type;
		}
	}
}