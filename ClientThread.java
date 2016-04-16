import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;

public class ClientThread extends Thread {
	private DatagramSocket socket;
	private RTPSocket rtpSocket;
	private static final String ENCODING = "ISO-8859-1";
	long highestSeqNumGivenToApplication = -1;
	private static final int TIMEOUT = 50; //50 ms receive timeout
	private static final int ACK_TIMEOUT = 500; //how long to wait for ACK before resending in ms
	private static final int MAX_PACKET_SIZE = 1000; //bytes
	
	
	public ClientThread(DatagramSocket socket, RTPSocket rtpSocket) {
		this.socket = socket;
		this.rtpSocket = rtpSocket;

	}

	public void run() {
		System.out.println("client thread started");
		//where the work of the thread gets done

		while (true) {
			//fix combine multiple things in queue
			//send data
			Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
			long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
			int winSpaceLeft = rtpSocket.peerWinSize - rtpSocket.numBytesUnacked - Packet.getHeaderSize() * rtpSocket.unAckedPackets.size();

			if (rtpSocket.dataOutQueue.peek() != null
					&& winSpaceLeft > Packet.getHeaderSize()) { //if use while, not if could have some issues with dominating the connection if the queue is constantly populated
				if (winSpaceLeft > 0) {
					System.out.println("win space left: " + winSpaceLeft);
				}

				byte[] removedBytes = rtpSocket.dataOutQueue.poll();
				int maxDataSize = MAX_PACKET_SIZE - Packet.getHeaderSize(); //the most data we can send without going over 1000 byte packet size limit
				int winSpaceLeftForData = winSpaceLeft - Packet.getHeaderSize();
				int amtOfDataToPutInPacket = winSpaceLeftForData > maxDataSize ? maxDataSize : winSpaceLeftForData;
				amtOfDataToPutInPacket = amtOfDataToPutInPacket > removedBytes.length ? removedBytes.length : amtOfDataToPutInPacket;

				byte[] pktData = new byte[amtOfDataToPutInPacket];
				System.arraycopy(removedBytes, 0, pktData, 0, amtOfDataToPutInPacket);

				//put the data in a packet and send it
				Packet packet = new Packet(pktData, Packet.DATA, rtpSocket.seqNum++, rtpSocket.rcvWinSize); //the rtp packet

				DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
				try {
					socket.send(sndPkt);
				} catch (IOException e) {
					System.out.println("issue sending packet");
				}
				rtpSocket.unAckedPackets.add(packet);
				rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
				rtpSocket.totalBytesSent += amtOfDataToPutInPacket;
				rtpSocket.numBytesUnacked += amtOfDataToPutInPacket;
				//System.out.println("# of unacked bytes increased to: " + rtpSocket.numBytesUnacked);
				


				//put the data you wont be sendign at front of queue if need be
				int numBytesUnsent = removedBytes.length - amtOfDataToPutInPacket;
				if (numBytesUnsent > 0) {
					byte[] unsentBytes = new byte[numBytesUnsent];
					System.arraycopy(removedBytes, amtOfDataToPutInPacket, unsentBytes, 0, numBytesUnsent);
					rtpSocket.dataOutQueue.offerFirst(unsentBytes); //put unsent bytes back at the beginning of the queue
				}
				
			}

			//resend unacked packets which have timed out
			for (Packet packet: rtpSocket.unAckedPktToTimeSentMap.keySet()) {
				long timeSent = rtpSocket.unAckedPktToTimeSentMap.get(packet);
				if (System.currentTimeMillis() - timeSent > ACK_TIMEOUT) { //ack not received in time, so resend
					DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
					try {
						socket.send(sndPkt);
					} catch (IOException e) {
						System.out.println("issue sending packet");
					}
					rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
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
				Packet received = new Packet(rcvdBytes);

				if (received.isData()) {
					if (received.seqNum <= rtpSocket.getHighestAcceptableRcvSeqNum()) { //check if packet fits in buffer (rceive window) of the socket on this computer
						if (!rtpSocket.bufferList.contains(received)) { //make sure we haven't received this packet already CONSIDER SIMPLY CHECKING SEQUENCE NUMBERS
							rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
							rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can
						}
						// ack the received packet even if we have it already
						System.out.println("received: " + received.seqNum + ", ACKing");
						Packet ackPack = new Packet(new byte[0], Packet.ACK, received.seqNum, rtpSocket.rcvWinSize);

						
						try {
							socket.send(new DatagramPacket(ackPack.getBytes(), ackPack.getBytes().length, rcvPkt.getAddress(), rcvPkt.getPort()));
						} catch (IOException e) {
							System.out.println("issue sending ACK");
						}

						rtpSocket.peerWinSize = received.winSize;
					} else {
						System.out.println("had to reject a packet since it wouldn't fit in buffer");
					}
				} else if (received.isAck()) {
					System.out.println("got an ack: " + received.seqNum);
					//stop caring about packets you've sent once they are ACKed
					Iterator<Packet> pListIter = rtpSocket.unAckedPackets.iterator();
					while (pListIter.hasNext()) {
						Packet packet = pListIter.next();
						System.out.println("packet seqNum: " +  packet.seqNum);
						System.out.println("ack seqNum: " + received.seqNum);
						if (packet.seqNum == received.seqNum) {
							pListIter.remove();
							rtpSocket.unAckedPktToTimeSentMap.remove(packet);
							rtpSocket.numBytesUnacked -= packet.getDataSize();
							System.out.println("# of unacked packets decreased to: " + rtpSocket.unAckedPackets.size());
							break;
						}
					}

					int seqNum = received.seqNum;
					if (seqNum > rtpSocket.highestSeqNumAcked) {
						rtpSocket.highestSeqNumAcked = seqNum;
					}

				}
			}
		}
	}
}
