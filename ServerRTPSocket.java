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
	 */
	public void accept() {
		Msg acceptMsg = new Msg("accept");
		msgsForThread.add(acceptMsg);
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
		private static AcceptStatus acceptStatus;
		private static InetAddress connReqAddr; //address of client requesting a connection
		private static int connReqPort; //port of client requesting a connection

		public ServerThread(DatagramSocket socket, ConcurrentLinkedQueue<Msg> msgs) {
			this.socket = socket;
			this.msgs = msgs;

			acceptStatus = null;
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
				System.out.println(rcvdString);

				//check if the packet is a connection initiation packet from the client( the first packet of a 3-wway handshake
				if (received.get("type").equals("initConnection") && AcceptStatus.AVAILABLE_FOR_CONNECTION == acceptStatus) { //if packet is a connection initilaization packet and seerver app has called accept()
					acceptStatus = AcceptStatus.RECEIVED_CONNECTION_REQUEST;
					connReqAddr = rcvPkt.getAddress();
					connReqPort = rcvPkt.getPort();
				} else {

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