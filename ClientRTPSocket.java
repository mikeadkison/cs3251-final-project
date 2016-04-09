import java.io.*;
import java.net.*;
import java.util.*;
import org.json.simple.*;

public class ClientRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private InetAddress serverIPAddress;
	private int serverUDPPort;

	public ClientRTPSocket(InetAddress IPAddress, int UDPport) {
		DatagramSocket socket = null;
		this.serverIPAddress = IPAddress;
		this.serverUDPPort = UDPport;

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

		
		JSONObject received = recv(socket);
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
		}

	}
	public void send(byte[] sendBytes) {

	}

	private JSONObject recv(DatagramSocket socket) {
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
		byte[] handshakeMsgBytes = null;
		try {
			handshakeMsgBytes = (handshakeMsg.toString() + "\n").getBytes(ENCODING);
		} catch (UnsupportedEncodingException e) {
			System.out.println("unsupported encoding");
			System.exit(-1);
		}
		DatagramPacket handshakePacket = new DatagramPacket(handshakeMsgBytes, handshakeMsgBytes.length, serverIPAddress, serverUDPPort);
		return handshakePacket;
	}
}