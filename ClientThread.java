import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.simple.*;

public class ClientThread extends Thread {
	private DatagramSocket socket;
	private RTPSocket rtpSocket;
	private static final String ENCODING = "ISO-8859-1";
	private final List<JSONObject> bufferList; //buffer used to hold packets until they can be sent to application
	long highestSeqNumGivenToApplication = -1;
	private static final int TIMEOUT = 50; //50 ms receive timeout
	
	public ClientThread(DatagramSocket socket, RTPSocket rtpSocket) {
		this.socket = socket;
		this.rtpSocket = rtpSocket;
		bufferList = new ArrayList<>();

	}

	public void run() {
		System.out.println("client thread started");
		//where the work of the thread gets done

		while (true) {

			while (rtpSocket.dataOutQueue.size() > 0) { //could have some issues with dominating the connection if the queue is constantly populated
				byte[] sendBytes = rtpSocket.dataOutQueue.poll();

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

			//receive data
			boolean receivedSomething = true;
			byte[] rcvdBytes = new byte[1000];
			DatagramPacket rcvPkt = new DatagramPacket(rcvdBytes, rcvdBytes.length);
			try {
				socket.setSoTimeout(TIMEOUT);
				socket.receive(rcvPkt);
			} catch (SocketTimeoutException e) {
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

				if (received.get("type").equals("data")) {
					bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
					updateDataToAppQueue(); //give the applications a chunk of data if you can

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
	 * look at the buffer and see what can be given to the application and put that in the dataInQueue
	 */
	private void updateDataToAppQueue() {
		long seqNum = highestSeqNumGivenToApplication + 1;
		boolean miss = false;
		while (!miss) { //while the packet with the next seqNum can be found in the buffer:
			for (int i = 0; i < bufferList.size(); i++) {
				JSONObject packet = bufferList.get(i);
				if ((Long) packet.get("seqNum") == seqNum) {
					String dataStr = (String) packet.get("data");
					byte[] dataBytes = null;
					try {
						dataBytes = dataStr.getBytes(ENCODING);
					} catch (UnsupportedEncodingException e) {
						System.out.println("unsupported encoding");
						System.exit(-1);
					}
					System.out.println("datainqueue now contains: " + dataStr);
					rtpSocket.dataInQueue.add(dataBytes);
					bufferList.remove(packet);
					i--;
					highestSeqNumGivenToApplication = seqNum;
					miss = false;
					System.out.println("added seqNum " + seqNum + " to application queue with size " + dataBytes.length);
					System.out.println("dataInQueueSize: " + rtpSocket.dataInQueue.size());
					continue;
				}
			}
			miss = true;
		}
	}
}