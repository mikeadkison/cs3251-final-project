import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.simple.*;

/**
 * used to connect to the server and then generate an RTPSocket for further communication
 */
public class ClientRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private InetAddress serverIPAddress;
	private int serverUDPPort;
	DatagramSocket socket;
	int seqNum;

	public ClientRTPSocket(InetAddress IPAddress, int UDPport) {
		this.serverIPAddress = IPAddress;
		this.serverUDPPort = UDPport;
		this.seqNum = 0;
	}

	public RTPSocket connect() {
		/*
		 * initialize connection with 3-way handshake
		 */
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			System.out.println("unable to create datagram socket");
			System.exit(-1);
		}

		// connection initiation section
		DatagramPacket connInitPacket = constructHandshakePacket("initConnection");

		
		try {
			socket.send(connInitPacket);
		} catch (IOException e) {
			System.out.println("issue sending connInitPacket");
			System.exit(-1);
		}

		
		JSONObject received = recv();
		if (received.get("type").equals("initConnectionConfirm")) {
			//send the last part of the 3-way handshake
			System.out.println("received initConnectionConfirm, ACKING");
			DatagramPacket connInitLastPacket = constructHandshakePacket("initConnectionConfirmAck");
			try {
				socket.send(connInitLastPacket);
			} catch (IOException e) {
				System.out.println("issue sending initConnectionConfirmAck");
				System.exit(-1);
			}

			ConcurrentLinkedQueue<byte[]> dataInQueue = new ConcurrentLinkedQueue<>();
			ConcurrentLinkedQueue<byte[]> dataOutQueue = new ConcurrentLinkedQueue<>();
			
			RTPSocket rtpSocket = new RTPSocket(serverIPAddress, serverUDPPort, dataInQueue, dataOutQueue);
			ClientThread clientThread = new ClientThread(socket, rtpSocket);
			clientThread.start();
			return rtpSocket;
		}

		return null;
	}

	private JSONObject recv() {
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
		return (JSONObject) JSONValue.parse(rcvdString);
	}

	private DatagramPacket constructHandshakePacket(String type) {
		// connection initiation section
		JSONObject handshakeMsg = new JSONObject();
		handshakeMsg.put("type", type);
		DatagramPacket handshakePacket = jsonToPacket(handshakeMsg);

		return handshakePacket;
	}

	private DatagramPacket jsonToPacket(JSONObject json) {
		byte[] bytes = null;
		try {
			bytes = (json.toString() + "\n").getBytes(ENCODING);
		} catch (UnsupportedEncodingException e) {
			System.out.println("unsupported encoding");
			System.exit(-1);
		}
		DatagramPacket packet = new DatagramPacket(bytes, bytes.length, serverIPAddress, serverUDPPort);
		return packet;
	}
}