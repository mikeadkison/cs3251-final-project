/**
 * TODO - add window size negotation from Packet
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * used to connect to the server and then generate an RTPSocket for further communication
 */
public class ClientRTPSocket {
	private static final String ENCODING = "ISO-8859-1";
	private InetAddress serverIPAddress;
	private int serverUDPPort;
	DatagramSocket socket;
	int maxWinSize; //max window size in packets of this client, not the peer of course
	int seqNum;
	private static final int CONNECTION_TIMEOUT = 500;

	public ClientRTPSocket(InetAddress IPAddress, int UDPport, int maxWinSize) {
		this.serverIPAddress = IPAddress;
		this.serverUDPPort = UDPport;
		this.seqNum = 0;
		this.maxWinSize = maxWinSize;
	}

	public RTPSocket connect() {
		/*
		 * initialize connection with 3-way handshake
		 */
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			
			System.exit(-1);
		}
		boolean connectionInitConfirmed = false;
		Packet received = null;
		int lastSentConnectionInit = -1;
		do {
			// connection initiation section
			Packet rtpInitPacket = new Packet(new byte[0], Packet.CONNECTION_INIT, 0, maxWinSize);
			DatagramPacket connInitPacket = new DatagramPacket(rtpInitPacket.getBytes(), rtpInitPacket.getBytes().length, serverIPAddress, serverUDPPort);

			
			try {
				socket.send(connInitPacket);
				
			} catch (IOException e) {
				
				System.exit(-1);
			}

			
			byte[] rcvdBytes = new byte[1000];
			DatagramPacket rcvPkt = new DatagramPacket(rcvdBytes, rcvdBytes.length);
			try {
				socket.setSoTimeout(CONNECTION_TIMEOUT);
			} catch (SocketException e) {
				e.printStackTrace();
			}

			boolean receivedSomething = true;
			try {
				socket.receive(rcvPkt);
			} catch (SocketTimeoutException e) {
				receivedSomething = false;
			} catch (IOException e) {
				receivedSomething = false;
				e.printStackTrace();
			}
			
			if (receivedSomething) {
				received = new Packet(rcvdBytes);
				if (received.checksumMatch && received.isConnectionInitConfirm()) {
					connectionInitConfirmed = true;
				}
			}
		} while (!connectionInitConfirmed);
		

		//send the last part of the 3-way handshake
		
		Packet rtpInitPacket = new Packet(new byte[0], Packet.CONNECTION_INIT_CONFIRM_ACK, 0, maxWinSize);
		DatagramPacket connInitLastPacket = new DatagramPacket(rtpInitPacket.getBytes(), rtpInitPacket.getBytes().length, serverIPAddress, serverUDPPort);
		try {
			socket.send(connInitLastPacket);
		} catch (IOException e) {
			
			System.exit(-1);
		}

		ConcurrentLinkedDeque<byte[]> dataInQueue = new ConcurrentLinkedDeque<>();
		ConcurrentLinkedDeque<byte[]> dataOutQueue = new ConcurrentLinkedDeque<>();
		
		int peerWinSize = received.winSize;
		

		RTPSocket rtpSocket = new RTPSocket(serverIPAddress, serverUDPPort, dataInQueue, dataOutQueue, maxWinSize, peerWinSize);
		ClientThread clientThread = new ClientThread(socket, rtpSocket);
		clientThread.start();
		return rtpSocket;


	}
}