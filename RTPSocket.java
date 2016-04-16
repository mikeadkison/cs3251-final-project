import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RTPSocket {
	protected InetAddress IP; //ip of peer
	protected int UDPport; //port of peer
	protected ConcurrentLinkedDeque<byte[]> dataInQueue; //the queue that holds data ready to be read
	protected ConcurrentLinkedDeque<byte[]> dataOutQueue; //the queue where the api puts data that it wants sent out
	protected int seqNum;
	protected int rcvWinSize; //the current size of the window (the buffer)
	private int maxRcvWinSize; //the biggest the window can get
	protected int peerWinSize; //the window size of the host you are connected to
	protected final List<Packet> bufferList = new LinkedList<>(); //the buffer for stuff received
	private long highestSeqNumGivenToApplication; //used to help figure out if a packet is a duplicate and should be ignored. packets with seq num <= this are no longer cared about/are no longer in buffer
	private static final String ENCODING = "ISO-8859-1";
	protected final List<Packet> unAckedPackets = new ArrayList<>();
	protected final Map<Packet, Long> unAckedPktToTimeSentMap = new HashMap<>();
	protected long highestSeqNumAcked; //highest seq num that we've sent that we received an ack for from our peer
	protected int numBytesUnacked; //number of data bytes unacked
	protected int totalBytesSent; //number of data bytes sent

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
		long seqNum = highestSeqNumGivenToApplication + 1;
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
					System.out.println("added seqNum " + seqNum + " to application in queue with size " + dataBytes.length);
					seqNum++;
					continue seqNumLoop;
				}
			}
			miss = true;
		}
	}

	/**
	 * get the highest seqnum that will fit in the buffer of this socket, which is also the highest seqnum that will be ACKed
	 *
	 * if a packet is received with a higher seqNum than this, it should not be put in the buffer or ACKed
	 */
	protected long getHighestAcceptableRcvSeqNum() {
		long lowestSeqNumInBuffer = getLowestSeqNumInList(bufferList);
		if (-1 == lowestSeqNumInBuffer) {
			lowestSeqNumInBuffer = highestSeqNumGivenToApplication + 1; //if there is nothing in the buffer, then the lowest sequence number that could be in the buffer in the future is = highest seq num given to application in queue + 1
		}
		return lowestSeqNumInBuffer + rcvWinSize - 1;
	}

	/**
	 * get the highest seqnum that the peer (remote host) is probably able to fit in its buffer
	 *
	 * data that would go in a packet with a seqNum > this should not be taken out of the socket's dataOutQueue
	 */
	protected long getHighestAcceptableRemoteSeqNum() {
		long lowestSeqNumUnacked = getLowestSeqNumInList(unAckedPackets);
		if (-1 == lowestSeqNumUnacked) {
			lowestSeqNumUnacked = highestSeqNumAcked + 1; //if there are no unacked packets then every seqNum before and including the highest seqNum has been acked
			                                              // the highestSeqNumAcked variable keeps track of what seqNums the remote buffer is able to hold even when every packet we've sent has been acked and
			                                              // forgotten about
		}
		return lowestSeqNumUnacked + peerWinSize - 1;
	}

	/**
	 * get the lowest sequence number in the buffer for received packets
	 *
	 * useful for figuring out the largest packet sequence number that you can accept
	 *
	 * @return -1 if nothing in list
	 */
	private long getLowestSeqNumInList(List<Packet> list) {
		if (list.size() == 0) {
			return -1;
		}

		long lowestSeqNum = list.get(0).seqNum;
		for (int i = 1; i < list.size(); i++) {
			long seqNum = list.get(i).seqNum;
			if (seqNum < lowestSeqNum) {
				lowestSeqNum = seqNum;
			}
		}
		return lowestSeqNum;
	}
}