
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
			socket = new DatagramSocket(UDPport, InetAddress.getByName("0.0.0.0"));
		} catch (SocketException e) {
			System.out.println("issue binding to listen port");
			e.printStackTrace();
			System.exit(-1);
		} catch (UnknownHostException e) {
			System.out.println("issue binding to socket ");
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
					
				}

				if (receivedSomething) {
					Packet received = new Packet(rcvdBytes);
					

					if (received.checksumMatch) { //only do stuff with packet if it isnt corrupt
						//check if the packet is a connection initiation packet from the client( the first packet of a 3-wway handshake
						if (received.isConnectionInit() && AcceptStatus.AVAILABLE_FOR_CONNECTION == acceptStatus) { //if packet is a connection initilaization packet and seerver app has called accept()
							acceptStatus = AcceptStatus.RECEIVED_CONNECTION_REQUEST;
							connReqAddr = rcvPkt.getAddress();
							connReqPort = rcvPkt.getPort();
							peerWinSize = received.winSize; 

						} else if (received.isConnectionInitConfirmAck() //received last part of 3-way handshake
									&& AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST == acceptStatus
									&& rcvPkt.getAddress().equals(connReqAddr) //make sure that the last part of the handshake came from the client you were expecting it to come from
									&& rcvPkt.getPort() == connReqPort) {
							acceptStatus = AcceptStatus.RECEIVED_ACK_TO_RESPONSE;

						} else if (received.isData()) {
							int index = rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())); //sometimes the data packets from client will reach server before the last part of the 3-way handshake. This is okay because the client will just resend the data when it isn't acked by the server
							if (index >= 0) {
								RTPSocket rtpSocket = rtpSockets.get(index);

								if (rtpSocket.bufferList.contains(received)) {
									ack(received, rtpSocket, rcvPkt);
								} else if (received.seqNum <= rtpSocket.highestSeqNumGivenToApplication) {
									ack(received, rtpSocket, rcvPkt);
									//System.out.println("acked " + received.seqNum + " again");
								} else {
									int numPacketsThatBufferCanHold = rtpSocket.rcvWinSize / Packet.getPacketSize();
									int highestAllowableSeqNum = rtpSocket.highestSeqNumGivenToApplication + numPacketsThatBufferCanHold;
									if (received.seqNum <= highestAllowableSeqNum) { //check if packet fits in buffer (rceive window) of the socket on this computer
										rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
										rtpSocket.rcvWinSize -= received.getPacketSize(); //decrease the window size by the size of the packet that was just put in it
										//rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can
										ack(received, rtpSocket, rcvPkt);
									} else {
										//System.out.println("had to reject packet #" + received.seqNum);
									}
								}

								rtpSocket.peerWinSize = received.winSize;

							}
						} else if (received.isAck()) {
							RTPSocket rtpSocket = rtpSockets.get(rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())));

							
							//stop caring about packets you've sent once they are ACKed
							Iterator<Packet> pListIter = rtpSocket.unAckedPackets.iterator();
							while (pListIter.hasNext()) {
								Packet packet = pListIter.next();
								
								
								if (packet.seqNum == received.seqNum) {
									pListIter.remove();
									rtpSocket.unAckedPktToTimeSentMap.remove(packet);
									rtpSocket.numBytesUnacked -= packet.getDataSize();
									if (rtpSocket.cwnd < rtpSocket.peerWinSize) { //increase cwnd
	                                    rtpSocket.cwnd += 1;
	                                }
									break;
								}
							}

							int seqNum = received.seqNum;
							if (seqNum > rtpSocket.highestSeqNumAcked) {
								rtpSocket.highestSeqNumAcked = seqNum;
							}
							//System.out.println(">>>>>>received ack #" + received.seqNum);

						} else if (received.isCloseACK()) {
							int index = rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())); //sometimes the data packets from client will reach server before the last part of the 3-way handshake. This is okay because the client will just resend the data when it isn't acked by the server
							if (index >= 0) {
								RTPSocket rtpSocket = rtpSockets.get(index);
								rtpSocket.disconnectConfirmed.set(true);
								rtpSockets.remove(rtpSocket);
							}
						}

						
					}
				}
				if (acceptStatus == AcceptStatus.RECEIVED_CONNECTION_REQUEST || acceptStatus == AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST) {
					System.out.println("received connection request");
					Packet connReqRespPkt = new Packet(new byte[0], Packet.CONNECTION_INIT_CONFIRM, 0, maxWinSize);
					byte[] connReqRespBytes = connReqRespPkt.getBytes();


					DatagramPacket connReqRespMsg = new DatagramPacket(connReqRespBytes, connReqRespBytes.length, connReqAddr, connReqPort);
					try {
						socket.send(connReqRespMsg);
					} catch (IOException e) {
						
						System.exit(-1);
					}
					acceptStatus = AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST;

				} else if (acceptStatus == AcceptStatus.RECEIVED_ACK_TO_RESPONSE) {
					System.out.println("received ack to connection request response, returning from accept");
					//at this point, the 3-way handshake is complete and the server must set up resources to receive data from the client
					acceptStatus = null;
					System.out.println("creating a new socket, peerwinsize " + peerWinSize);
					RTPSocket socketForClient = new RTPSocket(connReqAddr, connReqPort, new ConcurrentLinkedDeque<byte[]>(), new ConcurrentLinkedDeque<byte[]>(), maxWinSize, peerWinSize);
					rtpSockets.add(socketForClient);
					synchronized(lock) {
						readerSocket = socketForClient;
						lock.notify();
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
								
							}
							rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
							if (rtpSocket.cwnd > Packet.getPacketSize()) { //congestion control - decrease the congestion window if something times out
		                        rtpSocket.cwnd -= 1;
		                    }
		                    //System.out.println("packet #"+ packet.seqNum + " resent");
						}
					}

						

					//send data
					Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
					//long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
					int winSpaceLeft = Math.min(rtpSocket.peerWinSize, rtpSocket.cwnd) - rtpSocket.unAckedPackets.size() * Packet.getPacketSize();

					if (rtpSocket.dataOutQueue.peek() != null
							&& winSpaceLeft >= Packet.getPacketSize()) { //if use while, not if could have some issues with dominating the connection if the queue is constantly populated


						byte[] removedBytes = rtpSocket.dataOutQueue.poll();
						int maxDataSize = Packet.getPacketSize() - Packet.getHeaderSize(); //the most data we can send without going over 512 byte packet size limit

						int amtOfDataToPutInPacket = maxDataSize >= removedBytes.length ? removedBytes.length : maxDataSize;

						byte[] pktData = new byte[amtOfDataToPutInPacket];
						System.arraycopy(removedBytes, 0, pktData, 0, amtOfDataToPutInPacket);

						//put the data in a packet and send it
						Packet packet = new Packet(pktData, Packet.DATA, rtpSocket.seqNum++, rtpSocket.maxRcvWinSize); //the rtp packet

						DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
						try {
							socket.send(sndPkt);
						} catch (IOException e) {
							
						}
						rtpSocket.unAckedPackets.add(packet);
						rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
						rtpSocket.totalBytesSent += amtOfDataToPutInPacket;
						rtpSocket.numBytesUnacked += amtOfDataToPutInPacket;
						//
						


						//put the data you wont be sendign at front of queue if need be
						int numBytesUnsent = removedBytes.length - amtOfDataToPutInPacket;
						if (numBytesUnsent > 0) {
							byte[] unsentBytes = new byte[numBytesUnsent];
							System.arraycopy(removedBytes, amtOfDataToPutInPacket, unsentBytes, 0, numBytesUnsent);
							rtpSocket.dataOutQueue.offerFirst(unsentBytes); //put unsent bytes back at the beginning of the queue
						}
						
					}

					//disconnect if the socket wants to
					if (rtpSocket.disconnect.get()) {
						Packet packet = new Packet(new byte[0], Packet.CLOSE, rtpSocket.seqNum++, rtpSocket.maxRcvWinSize);

						DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
						try {
							socket.send(sndPkt);
						} catch (IOException e) {
							System.out.println("issue sending disconnect packet");
						}
					}
				}
			}
		}

		private void ack(Packet toAck, RTPSocket rtpSocket, DatagramPacket rcvPkt) {
			// ack the received packet even if we have it already
			
			Packet ackPack = new Packet(new byte[0], Packet.ACK, toAck.seqNum, rtpSocket.maxRcvWinSize);

			
			try {
				socket.send(new DatagramPacket(ackPack.getBytes(), ackPack.getBytes().length, rcvPkt.getAddress(), rcvPkt.getPort()));
			} catch (IOException e) {
				
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