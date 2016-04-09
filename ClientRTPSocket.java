import java.io.*;
import java.net.*;
import java.util.*;
import org.json.simple.*;

public class ClientRTPSocket {
	private static final String ENCODING = "ISO-8859-1";

	public ClientRTPSocket(InetAddress IPAddress, int UDPport) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			System.out.println("unable to create datagram socket");
			System.exit(-1);
		}

		// connection initiation section
		JSONObject connInitMsg = new JSONObject();
		connInitMsg.put("type", "initConnection");
		byte[] connInitMsgBytes = null;
		try {
			connInitMsgBytes = (connInitMsg.toString() + "\n").getBytes(ENCODING);
		} catch (UnsupportedEncodingException e) {
			System.out.println("unsupported encoding");
			System.exit(-1);
		}

		DatagramPacket connInitPacket = new DatagramPacket(connInitMsgBytes, connInitMsgBytes.length, IPAddress, UDPport);
		try {
			socket.send(connInitPacket);
		} catch (IOException e) {
			System.out.println("issue sending connInitPacket");
			System.exit(-1);
		}

		byte[] rcvdBytes = new byte[1000];
		DatagramPacket rcvPkt = new DatagramPacket(rcvdBytes, rcvdBytes.length);
		try {
			socket.receive(rcvPkt);
		} catch (IOException e) {
			System.out.println("issue receiving on socket" + socket);
			System.exit(0);
		}
		JSONObject received = packetToJSON(rcvPkt);
		System.out.println(received);

	}
	public void send(byte[] sendBytes) {

	}

	private JSONObject packetToJSON(DatagramPacket rcvPkt) {
		
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
}