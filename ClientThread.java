import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.simple.*;
import java.security.*;

public class ClientThread extends Thread {
	private DatagramSocket socket;
	private RTPSocket rtpSocket;
	private static final String ENCODING = "ISO-8859-1";
	long highestSeqNumGivenToApplication = -1;
	private static final int TIMEOUT = 50; //50 ms receive timeout
	private static final int ACK_TIMEOUT = 500; //how long to wait for ACK before resending in ms
	private static final int CHECKSUM_SIZE = 16; //length of checksum in bytes
	
	
	public ClientThread(DatagramSocket socket, RTPSocket rtpSocket) {
		this.socket = socket;
		this.rtpSocket = rtpSocket;

	}

	public void run() {
		System.out.println("client thread started");
		//where the work of the thread gets done

		while (true) {

			//send data
			Iterator<byte[]> dataOutQueueItr = rtpSocket.dataOutQueue.iterator();
			long highestAllowableSeqNum = rtpSocket.getHighestAcceptableRemoteSeqNum(); //the highest sequence number that can fit in the remote's buffer
			boolean seqNumsTooHigh = false; //stop trying to send data once we have too many unacked packets
			while (dataOutQueueItr.hasNext() && !seqNumsTooHigh) { //could have some issues with dominating the connection if the queue is constantly populated
				int seqNum = rtpSocket.seqNum;

				if (seqNum <= highestAllowableSeqNum) {
					byte[] sendBytes = dataOutQueueItr.next();
					rtpSocket.totalBytesSent += sendBytes.length;
					System.out.println("-----------");
					System.out.println("highestAllowableSeqNum: " + highestAllowableSeqNum);
					System.out.println("max peer win size: " + rtpSocket.peerWinSize);
					System.out.println("sent " + rtpSocket.totalBytesSent + " total bytes");
					System.out.println("highestSeqNumAcked by peer: " + rtpSocket.highestSeqNumAcked);
					dataOutQueueItr.remove();
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
						System.out.println("# of unacked packets increased to: " + rtpSocket.unAckedPackets.size());
						System.out.println("sent: " + dataAsString);
					} catch (IOException e) {
						System.out.println("issue sending packet");
					}
					rtpSocket.unAckedPackets.add(packetJSON);
					rtpSocket.unAckedPktToTimeSentMap.put(packetJSON, System.currentTimeMillis());
				} else { //the sequence number of this packet would be too high for the remote's buffer. Stop trying to send data after this iteration
					seqNumsTooHigh = true;
				}
			}

			//resend unacked packets which have timed out
			for (JSONObject packetJSON: rtpSocket.unAckedPktToTimeSentMap.keySet()) {
				long timeSent = rtpSocket.unAckedPktToTimeSentMap.get(packetJSON);
				if (System.currentTimeMillis() - timeSent > ACK_TIMEOUT) { //ack not received in time, so resend
					DatagramPacket sndPkt = jsonToPacket(packetJSON, rtpSocket.IP, rtpSocket.UDPport);
					try {
						socket.send(sndPkt);
					} catch (IOException e) {
						System.out.println("issue sending packet");
					}
					rtpSocket.unAckedPktToTimeSentMap.put(packetJSON, System.currentTimeMillis());
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
					if (((Number) received.get("seqNum")).longValue() <= rtpSocket.getHighestAcceptableRcvSeqNum()) { //check if packet fits in buffer (rceive window) of the socket on this computer
						rtpSocket.bufferList.add(received); //store the received packet (which is JSON) as a string in the appropriate buffer(the buffer associated with this client)
						rtpSocket.transferBufferToDataInQueue(); //give the applications a chunk of data if you can

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
					} else {
						System.out.println("had to reject a packet since it wouldn't fit in buffer");
					}
				} else if (received.get("type").equals("ACK")) {
					System.out.println("got an ack: " + received);
					//stop caring about packets once they are ACKed
					Iterator<JSONObject> pListIter = rtpSocket.unAckedPackets.iterator();
					while (pListIter.hasNext()) {
						JSONObject packet = pListIter.next();
						System.out.println("packet seqNum: " +  	packet.get("seqNum"));
						System.out.println("ack seqNum: " + received.get("seqNum"));
						if (((Number) packet.get("seqNum")).longValue() == ((Number) received.get("seqNum")).longValue()) {
							pListIter.remove();
							System.out.println("# of unacked packets decreased to: " + rtpSocket.unAckedPackets.size());
							break;
						}
					}

					long seqNum = ((Number) received.get("seqNum")).longValue();
					if (seqNum > rtpSocket.highestSeqNumAcked) {
						rtpSocket.highestSeqNumAcked = seqNum;
					}

				}
			}
		}
	}

    private byte[] combine(byte[] checksum, byte[] message) {
        byte[] combined = new byte[checksum.length + message.length];
        System.arraycopy(checksum, 0, combined, 0, checksum.length); //place checksum at beginning of packet
        System.arraycopy(message, 0, combined, checksum.length, message.length); //place rest of packet (which is in JSON format) after the checksum
        return combined;
    }

    private byte[] getChecksum(byte[] packet) {
        byte[] checksum = new byte[CHECKSUM_SIZE];
        System.arraycopy(packet, 0, checksum, 0, CHECKSUM_SIZE);
        return checksum;
    }

    private byte[] getMessage(byte[] packet) {
        int messageSize = packet.length - CHECKSUM_SIZE;
        byte[] message = new byte[messageSize];
        return message;
    }

    /**
     * @return 16 byte MD5 checksum of given bytes
     */
    private byte[] checksum(byte[] messageBytes) {
        MessageDigest msgDigest = null;
        try {
            msgDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("md5 not available for checksum");
            System.exit(-1);
        }

        msgDigest.update(messageBytes);
        return msgDigest.digest();
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

}
