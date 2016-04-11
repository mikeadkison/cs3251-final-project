import java.io.*;
import java.net.*;
import java.util.*;
import org.json.simple.*;
import java.util.concurrent.*;

public class ServerRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private ConcurrentLinkedQueue<Msg> msgsForThread;
	private static RTPSocket readerSocket; //will be created by the server thread when accept is called
	private static Object lock = new Object();
	private static final int TIMEOUT = 50; //50 ms receive timeout
	private static long maxWinSize; //the maximum receive window size in packets


	public ServerRTPSocket(int UDPport, long maxWinSize) {
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
		long peerWinSize; //the window size of the peer who we are currently setting up a connection to
		

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
					String rcvdString = null;
					try {
						rcvdString = new String(rcvPkt.getData(), ENCODING);
					} catch (UnsupportedEncodingException e) {
						System.out.println("unsupported encoding while decoding udp message");
						System.exit(-1);
					}
					rcvdString = rcvdString.substring(0, rcvdString.lastIndexOf("\n")); //get rid of extra bytes on end of stringg
					JSONObject received = (JSONObject) JSONValue.parse(rcvdString);

					//check if the packet is a connection initiation packet from the client( the first packet of a 3-wway handshake
					if (received.get("type").equals("initConnection") && AcceptStatus.AVAILABLE_FOR_CONNECTION == acceptStatus) { //if packet is a connection initilaization packet and seerver app has called accept()
						acceptStatus = AcceptStatus.RECEIVED_CONNECTION_REQUEST;
						connReqAddr = rcvPkt.getAddress();
						connReqPort = rcvPkt.getPort();
						peerWinSize = (Long) received.get("winSize");

					} else if (received.get("type").equals("initConnectionConfirmAck") //received last part of 3-way handshake
								&& AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST == acceptStatus
								&& rcvPkt.getAddress().equals(connReqAddr) //make sure that the last part of the handshake came from the client you were expecting it to come from
								&& rcvPkt.getPort() == connReqPort) {
						acceptStatus = AcceptStatus.RECEIVED_ACK_TO_RESPONSE;

					} else if (received.get("type").equals("data")) {
						RTPSocket rtpSocket = rtpSockets.get(rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())));
						
						if (((Number) received.get("seqNum")).longValue() <= rtpSocket.getHighestAcceptableRcvSeqNum()) { //check if packet fits in buffer (rceive window) of the socket on this computer
							rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
							rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can

							// ack the received packet
							System.out.println("received: " + received + ", ACKing");
							JSONObject ackJSON = new JSONObject();
							ackJSON.put("type", "ACK");
							ackJSON.put("seqNum",  received.get("seqNum"));

							
							try {
								socket.send(jsonToPacket(ackJSON, rcvPkt.getAddress(), rcvPkt.getPort()));
							} catch (IOException e) {
								System.out.println("issue sending ACK");
							}
						} else {
							System.out.println("had to reject a packet since it wouldn't fit in buffer");
						}
					} else if (received.get("type").equals("ACK")) {
						RTPSocket rtpSocket = rtpSockets.get(rtpSockets.indexOf(new RTPSocket(rcvPkt.getAddress(), rcvPkt.getPort())));

						System.out.println("got an ack: " + received);
						//stop caring about packets once they are ACKed
						Iterator<JSONObject> pListIter = rtpSocket.unAckedPackets.iterator();
						while (pListIter.hasNext()) {
							JSONObject packet = pListIter.next();
							System.out.println("packet seqNum: " + packet.get("seqNum"));
							System.out.println("ack seqNum: " + received.get("seqNum"));
							if (((Number) packet.get("seqNum")).longValue() == ((Number) received.get("seqNum")).longValue()) {
								pListIter.remove();
								System.out.println("# of unacked packets decreased to: " + rtpSocket.unAckedPackets.size());
								break;
							}
						}

						long seqNum = ((Number) received.get("seqNum")).longValue();
						if (seqNum > rtpSocket.highestSeqNumAcked) {
							rtpSocket.highestSeqNumAcked = seqNum;
						}
					}

					if (acceptStatus == AcceptStatus.RECEIVED_CONNECTION_REQUEST) {
						System.out.println("received connection request");
						System.out.println("responding...");

						//responding to connection request
						JSONObject connReqRespMsgJSON = new JSONObject();
						connReqRespMsgJSON.put("type", "initConnectionConfirm");
						connReqRespMsgJSON.put("winSize", maxWinSize);
						System.out.println(connReqRespMsgJSON);
						byte[] connReqRespBytes = null;
						try {
							connReqRespBytes = (connReqRespMsgJSON.toString() + "\n").getBytes(ENCODING);
						} catch (UnsupportedEncodingException e) {
							System.out.println("unsupported encoding");
							System.exit(-1);
						}

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
						RTPSocket socketForClient = new RTPSocket(connReqAddr, connReqPort, new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(), maxWinSize, peerWinSize);
						rtpSockets.add(socketForClient);
						synchronized(lock) {
							readerSocket = socketForClient;
							lock.notify();
						}
					}
				}


				//for every RTPSocket, send stuff the socket wants to send (to clients)
				for (RTPSocket rtpSocket: rtpSockets) {
					//send data
					Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
					long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
					boolean seqNumsTooHigh = false; //stop trying to send data once we have too many unacked packets
					while (dataOutQueueItr.hasNext() && !seqNumsTooHigh) { //could have some issues with dominating the connection if the queue is constantly populated
						int seqNum = rtpSocket.seqNum;

						if (seqNum <= highestAllowableSeqNum) {
							byte[] sendBytes = dataOutQueueItr.next();
							rtpSocket.totalBytesSent += sendBytes.length;
							System.out.println("-----------");
							System.out.println("highestAllowableSeqNum: " + highestAllowableSeqNum);
							System.out.println("max peer win size: " + rtpSocket.peerWinSize);
							System.out.println("sent " + rtpSocket.totalBytesSent + " total bytes");
							System.out.println("highestSeqNumAcked by peer: " + rtpSocket.highestSeqNumAcked);
							dataOutQueueItr.remove();
							//put the data in a packet and send it
							String dataAsString = null;
							try {
								dataAsString = new String(sendBytes, ENCODING);
							} catch (UnsupportedEncodingException e) {
								System.out.println ("issue encoding");
							}
							
							JSONObject packetJSON = new JSONObject();
							packetJSON.put("type", "data");
							packetJSON.put("data", dataAsString);
							packetJSON.put("seqNum", rtpSocket.seqNum);
							rtpSocket.seqNum++;

							DatagramPacket sndPkt = jsonToPacket(packetJSON, rtpSocket.IP, rtpSocket.UDPport);
							try {
								socket.send(sndPkt);
								rtpSocket.unAckedPackets.add(packetJSON);
								System.out.println("# of unacked packets increased to: " + rtpSocket.unAckedPackets.size());
								System.out.println("sent: " + dataAsString);
							} catch (IOException e) {
								System.out.println("issue sending packet");
							}
						} else { //the sequence number of this packet would be too high for the remote's buffer. Stop trying to send data after this iteration
							seqNumsTooHigh = true;
						}

					}
				}
			}
		}

		private DatagramPacket jsonToPacket(JSONObject json, InetAddress destIP, int destPort) {
			byte[] bytes = null;
			try {
				bytes = (json.toString() + "\n").getBytes(ENCODING);
			} catch (UnsupportedEncodingException e) {
				System.out.println("unsupported encoding");
				System.exit(-1);
			}
			DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destIP, destPort);
			return packet;
		}

	}

	private class Msg {
		private String type;
		public Msg(String type) {
			this.type = type;
		}
	}
}