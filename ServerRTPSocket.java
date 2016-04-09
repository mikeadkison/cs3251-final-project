import java.io.*;
import java.net.*;
import java.util.*;
import org.json.simple.*;
import java.util.concurrent.*;

public class ServerRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private ConcurrentLinkedQueue<Msg> msgsForThread;


	public ServerRTPSocket(int UDPport) {
		DatagramSocket socket = null;
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
	public ServerRTPReaderSocket accept() {
		Msg acceptMsg = new Msg("accept");
		msgsForThread.add(acceptMsg);
		return null;
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
		private Map<ServerRTPReaderSocket, Queues> clientToBufferMap;
		

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
					socket.receive(rcvPkt);
				} catch (IOException e) {
					System.out.println("issue receiving on socket" + socket);
					System.exit(0);
				}
				String rcvdString = null;
				try {
					rcvdString = new String(rcvPkt.getData(), ENCODING);
				} catch (UnsupportedEncodingException e) {
					System.out.println("unsupported encoding while decoding udp message");
					System.exit(-1);
				}

				rcvdString = rcvdString.substring(0, rcvdString.lastIndexOf("\n")); //get rid of extra bytes on end of stringg
				JSONObject received = (JSONObject) JSONValue.parse(rcvdString);
				//System.out.println(received);

				//check if the packet is a connection initiation packet from the client( the first packet of a 3-wway handshake
				if (received.get("type").equals("initConnection") && AcceptStatus.AVAILABLE_FOR_CONNECTION == acceptStatus) { //if packet is a connection initilaization packet and seerver app has called accept()
					acceptStatus = AcceptStatus.RECEIVED_CONNECTION_REQUEST;
					connReqAddr = rcvPkt.getAddress();
					connReqPort = rcvPkt.getPort();

				} else if (received.get("type").equals("initConnectionConfirmAck") //received last part of 3-way handshake
							&& AcceptStatus.RESPONDED_TO_CONNECTION_REQUEST == acceptStatus
							&& rcvPkt.getAddress().equals(connReqAddr) //make sure that the last part of the handshake came from the client you were expecting it to come from
							&& rcvPkt.getPort() == connReqPort) {
					acceptStatus = AcceptStatus.RECEIVED_ACK_TO_RESPONSE;

				} else if (received.get("type").equals("data")) {
					Queues queues = clientToBufferMap.get(new ServerRTPReaderSocket(connReqAddr, connReqPort));
					queues.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
					queues.updateAppQueue(); //give the applications a chunk of data if you can
				}

				if (acceptStatus == AcceptStatus.RECEIVED_CONNECTION_REQUEST) {
					System.out.println("received connection request");
					System.out.println("responding...");

					//responding to connection request
					JSONObject connReqRespMsgJSON = new JSONObject();
					connReqRespMsgJSON.put("type", "initConnectionConfirm");
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
					ServerRTPReaderSocket client = new ServerRTPReaderSocket(connReqAddr, connReqPort);
					clientToBufferMap.put(client, new Queues());
				}
			}
		}

		/**
		 * contains two queues
		 * the first queue is size-limited - it serves as the packet buffer for each client
		 * the second queue will contain in-order data which is ready for the application to consume via the api
		 *
		 * the second queue will be derived from the first
		 */
		private class Queues {
			private final List<JSONObject> bufferList = new LinkedList<>(); //the first queue (see above)
			private final ConcurrentLinkedQueue<byte[]> appQueue = new ConcurrentLinkedQueue<>();
			private long highestSeqNumGivenToApplication; //used to help figure out if a packet is a duplicate and should be ignored

			public Queues() {
				highestSeqNumGivenToApplication = -1; //nothing has been given to application yet
			}

			/**
			 * look at the buffer and see what can be given to the application and put that in the appQueue
			 */
			private void updateAppQueue() {
				long seqNum = highestSeqNumGivenToApplication + 1;
				boolean miss = false;
				while (!miss) { //while the packet with the next seqNum can be found in the buffer:
					for (JSONObject packet: bufferList) {
						if ((Long) packet.get("seqNum") == seqNum) {
							String dataStr = (String) packet.get("data");
							byte[] dataBytes = null;
							try {
								dataBytes = (dataStr + "\n").getBytes(ENCODING);
							} catch (UnsupportedEncodingException e) {
								System.out.println("unsupported encoding");
								System.exit(-1);
							}
							appQueue.add(dataBytes);
							bufferList.remove(packet);
							highestSeqNumGivenToApplication = seqNum;
							miss = false;
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