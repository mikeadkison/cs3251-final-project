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
	int maxWinSize; //max window size in packets
	int seqNum;

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
			System.out.println("unable to create datagram socket");
			System.exit(-1);
		}

		// connection initiation section
		Packet rtpInitPacket = new Packet(new byte[0], Packet.CONNECTION_INIT, 0, maxWinSize);
		DatagramPacket connInitPacket = new DatagramPacket(rtpInitPacket.getBytes(), rtpInitPacket.getBytes().length, serverIPAddress, serverUDPPort);

		
		try {
			socket.send(connInitPacket);
			System.out.println("sent connInitPacket of size: " + rtpInitPacket.getBytes().length);
		} catch (IOException e) {
			System.out.println("issue sending connInitPacket");
			System.exit(-1);
		}

		
		byte[] rcvdBytes = new byte[1000];
		DatagramPacket rcvPkt = new DatagramPacket(rcvdBytes, rcvdBytes.length);
		try {
			socket.receive(rcvPkt);
		} catch (IOException e) {
			System.out.println("issue receiving packet during connection setup");
		}
		
		Packet received = new Packet(rcvdBytes);
		System.out.println("received a response to our connection request. Is it a connectioninit");

		if (received.isConnectionInitConfirm()) {
			//send the last part of the 3-way handshake
			System.out.println("received initConnectionConfirm, ACKING");
			rtpInitPacket = new Packet(new byte[0], Packet.CONNECTION_INIT_CONFIRM_ACK, 0, maxWinSize);
			DatagramPacket connInitLastPacket = new DatagramPacket(rtpInitPacket.getBytes(), rtpInitPacket.getBytes().length, serverIPAddress, serverUDPPort);
			try {
				socket.send(connInitLastPacket);
			} catch (IOException e) {
				System.out.println("issue sending initConnectionConfirmAck");
				System.exit(-1);
			}

			ConcurrentLinkedDeque<byte[]> dataInQueue = new ConcurrentLinkedDeque<>();
			ConcurrentLinkedDeque<byte[]> dataOutQueue = new ConcurrentLinkedDeque<>();
			
			int peerWinSize = received.winSize;
			System.out.println("peer win size: " + peerWinSize);

			RTPSocket rtpSocket = new RTPSocket(serverIPAddress, serverUDPPort, dataInQueue, dataOutQueue, maxWinSize, peerWinSize);
			ClientThread clientThread = new ClientThread(socket, rtpSocket);
			clientThread.start();
			return rtpSocket;
		}

		return null;
	}
}