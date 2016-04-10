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
		private Map<RTPSocket, Queues> clientToBufferMap;
		long peerWinSize; //the window size of the peer who we are currently setting up a connection to
		

		public ServerThread(DatagramSocket socket, ConcurrentLinkedQueue<Msg> msgs) {
			this.socket = socket;
			this.msgs = msgs;

			acceptStatus = null;
			clientToBufferMap = new HashMap<>();
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
						Queues queues = clientToBufferMap.get(new RTPSocket(connReqAddr, connReqPort));
						queues.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
						queues.updateDataToAppQueue(); //give the applications a chunk of data if you can

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
						Queues clientQueues = new Queues();
						RTPSocket socketForClient = new RTPSocket(connReqAddr, connReqPort, clientQueues.dataToAppQueue, clientQueues.dataFromAppQueue, maxWinSize, peerWinSize);
						clientToBufferMap.put(socketForClient, clientQueues);
						synchronized(lock) {
							readerSocket = socketForClient;
							lock.notify();
						}
					}
				}


				//for every RTPSocket, send stuff the socket wants to send (to clients)
				for (RTPSocket rtpSocket: clientToBufferMap.keySet()) {

/*					try {
						sleep(0);
					} catch (InterruptedException e) {
						System.out.println("interrupted");
					}*/
					ConcurrentLinkedQueue<byte[]> stuffToSend = rtpSocket.dataOutQueue;
					while (stuffToSend.size() > 0) { //could have some issues with dominating the connection if the queue is constantly populated
						byte[] sendBytes = stuffToSend.poll();

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
							System.out.println("sent: " + dataAsString);
						} catch (IOException e) {
							System.out.println("issue sending packet");
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

		/**
		 * contains three queues
		 * the first queue is size-limited - it serves as the packet buffer for each client
		 * the second queue will contain in-order data which is ready for the application to consume via the api
		 *
		 * the second queue will be derived from the first
		 *
		 * the third queue contains data that the api has been asked by the application to send out
		 */
		private class Queues {
			private final List<JSONObject> bufferList = new LinkedList<>(); //the first queue (see above)
			private final ConcurrentLinkedQueue<byte[]> dataToAppQueue = new ConcurrentLinkedQueue<>(); //contains in-order data ready for api to put together for application
			private final ConcurrentLinkedQueue<byte[]> dataFromAppQueue = new ConcurrentLinkedQueue<>(); //contains data which the application wants sent. The data will already be sized properly for packets
			private long highestSeqNumGivenToApplication; //used to help figure out if a packet is a duplicate and should be ignored

			public Queues() {
				highestSeqNumGivenToApplication = -1; //nothing has been given to application yet
			}

			/**
			 * look at the buffer and see what can be given to the application and put that in the dataToAppQueue
			 */
			private void updateDataToAppQueue() {
				long seqNum = highestSeqNumGivenToApplication + 1;
				boolean miss = false;
				while (!miss) { //while the packet with the next seqNum can be found in the buffer:
					for (JSONObject packet: bufferList) {
						if ((Long) packet.get("seqNum") == seqNum) {
							String dataStr = (String) packet.get("data");
							byte[] dataBytes = null;
							try {
								dataBytes = dataStr.getBytes(ENCODING);
							} catch (UnsupportedEncodingException e) {
								System.out.println("unsupported encoding");
								System.exit(-1);
							}
							dataToAppQueue.add(dataBytes);
							bufferList.remove(packet);
							highestSeqNumGivenToApplication = seqNum;
							miss = false;
							System.out.println("added seqNum " + seqNum + " to application in queue with size " + dataBytes.length);
							continue;
						}
					}
					miss = true;
				}
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