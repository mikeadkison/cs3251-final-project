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

	}
	public void send(byte[] sendBytes) {

	}
}