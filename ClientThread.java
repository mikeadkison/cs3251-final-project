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
	private static final int ACK_TIMEOUT = 1000; //how long to wait for ACK before resending in ms
	
	
	public ClientThread(DatagramSocket socket, RTPSocket rtpSocket) {
		this.socket = socket;
		this.rtpSocket = rtpSocket;

	}

	private void ack(Packet toAck, RTPSocket rtpSocket, DatagramPacket rcvPkt) {
		// ack the received packet even if we have it already
		
		Packet ackPack = new Packet(new byte[0], Packet.ACK, toAck.seqNum, rtpSocket.maxRcvWinSize);

		
		try {
			socket.send(new DatagramPacket(ackPack.getBytes(), ackPack.getBytes().length, rcvPkt.getAddress(), rcvPkt.getPort()));
		} catch (IOException e) {
			
		}
	}

	@Override
	public void run() {
		
		//where the work of the thread gets done

		while (true) {
			//fix combine multiple things in queue
			//send data
			Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
			//long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
			int winSpaceLeft = Math.min(rtpSocket.peerWinSize, rtpSocket.cwnd) - rtpSocket.unAckedPackets.size() * Packet.getPacketSize();

			if (rtpSocket.dataOutQueue.peek() != null
					&& winSpaceLeft >= Packet.getPacketSize()) { //if use while, not if could have some issues with dominating the connection if the queue is constantly populated


				byte[] removedBytes = rtpSocket.dataOutQueue.poll();
				int maxDataSize = Packet.getPacketSize() - Packet.getHeaderSize(); //the most data we can send without going over 512 byte packet size limit

				int amtOfDataToPutInPacket = maxDataSize >= removedBytes.length ? removedBytes.length : maxDataSize;

				byte[] pktData = new byte[amtOfDataToPutInPacket];
				System.arraycopy(removedBytes, 0, pktData, 0, amtOfDataToPutInPacket);

				//put the data in a packet and send it
				Packet packet = new Packet(pktData, Packet.DATA, rtpSocket.seqNum++, rtpSocket.maxRcvWinSize); //the rtp packet

				DatagramPacket sndPkt = new DatagramPacket(packet.getBytes(), packet.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
				try {
					socket.send(sndPkt);
				} catch (IOException e) {
					
				}
				rtpSocket.unAckedPackets.add(packet);
				rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
				rtpSocket.totalBytesSent += amtOfDataToPutInPacket;
				rtpSocket.numBytesUnacked += amtOfDataToPutInPacket;
				//
				


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
						
					}
					rtpSocket.unAckedPktToTimeSentMap.put(packet, System.currentTimeMillis());
                    if (rtpSocket.cwnd > Packet.getPacketSize()) { //congestion control - decrease the congestion window if something times out
                        rtpSocket.cwnd -= 1;
                    }
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
				
			}

			if (receivedSomething) {
				Packet received = new Packet(rcvdBytes);
				if (received.checksumMatch) {//first make sure the checksum matches the rest of the packet
					if (received.isData()) {	
						if (rtpSocket.bufferList.contains(received)) {
							ack(received, rtpSocket, rcvPkt);
						} else if (received.seqNum <= rtpSocket.highestSeqNumGivenToApplication) {
							ack(received, rtpSocket, rcvPkt);
							//System.out.println("acked " + received.seqNum + " again");
						} else {
							int numPacketsThatBufferCanHold = rtpSocket.rcvWinSize / Packet.getPacketSize();
							int highestAllowableSeqNum = rtpSocket.highestSeqNumGivenToApplication + numPacketsThatBufferCanHold;
							if (received.seqNum <= highestAllowableSeqNum) { //check if packet fits in buffer (rceive window) of the socket on this computer
								rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
								rtpSocket.rcvWinSize -= received.getPacketSize(); //decrease the window size by the size of the packet that was just put in it
								//rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can
								ack(received, rtpSocket, rcvPkt);
							} else {
								//System.out.println("had to reject packet #" + received.seqNum);
							}
						}
								

						rtpSocket.peerWinSize = received.winSize;

					} else if (received.isAck()) {
						
						//stop caring about packets you've sent once they are ACKed
						Iterator<Packet> pListIter = rtpSocket.unAckedPackets.iterator();
						while (pListIter.hasNext()) {
							Packet packet = pListIter.next();
							
							
							if (packet.seqNum == received.seqNum) {
								pListIter.remove();
								rtpSocket.unAckedPktToTimeSentMap.remove(packet);
								rtpSocket.numBytesUnacked -= packet.getDataSize();
								if (rtpSocket.cwnd < rtpSocket.peerWinSize) { //increase cwnd
                                    rtpSocket.cwnd += 1;
                                }
								break;
							}
						}

						int seqNum = received.seqNum;
						if (seqNum > rtpSocket.highestSeqNumAcked) {
							rtpSocket.highestSeqNumAcked = seqNum;
						}

					} else if (received.isConnectionInitConfirm()) { //send the last part of the 3-way handshake
						Packet rtpInitPacket = new Packet(new byte[0], Packet.CONNECTION_INIT_CONFIRM_ACK, 0, rtpSocket.rcvWinSize);
						DatagramPacket connInitLastPacket = new DatagramPacket(rtpInitPacket.getBytes(), rtpInitPacket.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
						try {
							socket.send(connInitLastPacket);
						} catch (IOException e) {
							
							System.exit(-1);
						}

					} else if (received.isClose()) {
						Packet closeACK = new Packet(new byte[0], Packet.CLOSE_ACK, 0, rtpSocket.rcvWinSize);
						DatagramPacket closeACKUDPPkt = new DatagramPacket(closeACK.getBytes(), closeACK.getBytes().length, rtpSocket.IP, rtpSocket.UDPport);
						try {
							socket.send(closeACKUDPPkt);
						} catch (IOException e) {
							
							System.exit(-1);
						}
					}
				}
			}
		}
	}

	
}
