import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RTPSocket {
	protected InetAddress IP; //ip of peer
	protected int UDPport; //port of peer
	protected ConcurrentLinkedDeque<byte[]> dataInQueue; //the queue that holds data ready to be read
	protected ConcurrentLinkedDeque<byte[]> dataOutQueue; //the queue where the api puts data that it wants sent out
	protected int seqNum;
	protected int rcvWinSize; //the current size of the window (the buffer)
	protected int maxRcvWinSize; //the biggest the window can get, also what is sent to peer
	protected int peerWinSize; //the window size of the host you are connected to
	protected final ConcurrentLinkedQueue<Packet> bufferList = new ConcurrentLinkedQueue<>(); //the buffer for stuff received
	protected int highestSeqNumGivenToApplication; //used to help figure out if a packet is a duplicate and should be ignored. packets with seq num <= this are no longer cared about/are no longer in buffer
	private static final String ENCODING = "ISO-8859-1";
	protected final List<Packet> unAckedPackets = new ArrayList<>();
	protected final Map<Packet, Long> unAckedPktToTimeSentMap = new HashMap<>();
	protected long highestSeqNumAcked; //highest seq num that we've sent that we received an ack for from our peer
	protected int numBytesUnacked; //number of data bytes unacked
	protected int totalBytesSent; //number of data bytes sent
	protected int cwnd; //congestion window - initially the same as the advertised peer window size
	protected AtomicBoolean disconnect; //used by this rtpsocket to signal to the thread that it wishes to disconnect from its peer
	protected AtomicBoolean disconnectConfirmed;

	public RTPSocket (InetAddress IP, int UDPport, ConcurrentLinkedDeque<byte[]> dataInQueue, ConcurrentLinkedDeque<byte[]> dataOutQueue, int maxRcvWinSize, int peerWinSize) {
		this(IP, UDPport);
		this.dataInQueue = dataInQueue;
		this.dataOutQueue = dataOutQueue;
		this.maxRcvWinSize = maxRcvWinSize;
		rcvWinSize = maxRcvWinSize;
		this.peerWinSize = peerWinSize;
		seqNum = 0;
		highestSeqNumGivenToApplication = -1;
		highestSeqNumAcked = -1;
		this.cwnd = peerWinSize;
		this.disconnect = new AtomicBoolean(false);
		this.disconnectConfirmed = new Atomicboolean(false);
	}

	/**
	 * used for map lookup by server
	 */
	public RTPSocket (InetAddress IP, int UDPport) {
		this.IP = IP;
		this.UDPport = UDPport;
	}

	/**
	 * gives the application a byte array of all the data received so far. The data is consumed
	 */
	public byte[] read() {
		this.transferBufferToDataInQueue();
		Object[] arrays = (Object[]) dataInQueue.toArray();

		int totalSize = 0;
		for (int i = 0; i < arrays.length; i++) {
			totalSize += ((byte[]) arrays[i]).length;
		}
		

		byte[] allDataArr = new byte[totalSize]; //this byte array will contain all of the data from the stream so far and will be returned to the application
		int amtCopiedSoFar = 0;
		for (int i = 0; i < arrays.length; i++) {
			System.arraycopy((byte[]) arrays[i], 0, allDataArr, amtCopiedSoFar, ((byte[]) arrays[i]).length);
			amtCopiedSoFar += ((byte[]) arrays[i]).length;
			dataInQueue.remove(arrays[i]);
		}

		return allDataArr;
	}

	public void disconnect() {
		disconnect.set(true);
	}

	/**
	 * sends something
	 * data is split into 500 byte sections to leave lots of room for packet headers and fluff
	 */
	public void send(byte[] sendBytes) {
		/*final int DATA_PER_PACKET = 500;
		for (int offset = 0; offset < sendBytes.length; offset += DATA_PER_PACKET) {
			int length = offset + DATA_PER_PACKET <= sendBytes.length ? DATA_PER_PACKET : sendBytes.length - offset; //how much data will be copied from sendBytes
			byte[] dataToSend = new byte[length];
			System.arraycopy(sendBytes, offset, dataToSend, 0, length);
			dataOutQueue.add(dataToSend);
		}*/
		dataOutQueue.add(sendBytes);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RTPSocket)) {
			return false;
		}
		final RTPSocket theOther = (RTPSocket) other;
		return this.IP.equals(theOther.IP) && this.UDPport == theOther.UDPport;
	}

	@Override
	public int hashCode() {
		return this.IP.hashCode() + this.UDPport * 13;
	}

	/**
	 * look at the buffer and see what can be given to the application and put that in the dataInQueue
	 */
	protected void transferBufferToDataInQueue() {
		int seqNum = highestSeqNumGivenToApplication + 1;
		boolean miss = false;
		seqNumLoop: while (!miss) { //while the packet with the next seqNum can be found in the buffer:
			for (Packet packet: bufferList) {
				if (packet.seqNum == seqNum) {
					byte[] dataBytes = packet.data;

					dataInQueue.add(dataBytes);
					bufferList.remove(packet);
					this.rcvWinSize += packet.getPacketSize(); //increase the window size since a packet has been removed from the buffer
					highestSeqNumGivenToApplication = seqNum;
					miss = false;
					System.out.println("acked " + packet.seqNum);
					seqNum++;
					continue seqNumLoop;
				}
			}
			miss = true;
		}
	}

	private void ack(Packet toAck, DatagramSocket socket) {
		// ack the received packet even if we have it already
		
		Packet ackPack = new Packet(new byte[0], Packet.ACK, toAck.seqNum, this.maxRcvWinSize);

		
		try {
			socket.send(new DatagramPacket(ackPack.getBytes(), ackPack.getBytes().length, IP, UDPport));
		} catch (IOException e) {
			
		}
	}

	
}