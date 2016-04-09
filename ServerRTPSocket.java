import java.io.*;
import java.net.*;
import java.util.*;
import org.json.simple.*;

public class ServerRTPSocket {
	public ServerRTPSocket(int UDPport) {
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket(UDPport);
		} catch (SocketException e) {
			System.out.println("unable to create datagram socket");
			System.exit(-1);
		}

		(new ServerThread(socket)).start();
	}
	public void accept() {

	}
	public byte[] read() {
		return null;
	}

	private class ServerThread extends Thread {
		private DatagramSocket socket;
		public ServerThread(DatagramSocket socket) {
			this.socket = socket;
		}
		public void run() {
			System.out.println("server thread started");
		}
	}
}