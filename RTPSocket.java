import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.simple.*;

public class RTPSocket {
	protected InetAddress IP; //ip of peer
	protected int UDPport; //porrt of peer
	protected ConcurrentLinkedQueue<byte[]> dataInQueue; //the queue that holds data ready to be read
	protected ConcurrentLinkedQueue<byte[]> dataOutQueue; //the queue where the api puts data that it wants sent out
	protected int seqNum;
	protected long rcvWinSize; //the current size of the window (the buffer)
	private long maxRcvWinSize; //the biggest the window can get
	protected long peerWinSize; //the window size of the host you are connected to
	protected long unAckedPackets;
	protected final List<JSONObject> bufferList = new LinkedList<>();;
	private long highestSeqNumGivenToApplication; //used to help figure out if a packet is a duplicate and should be ignored
	private static final String ENCODING = "ISO-8859-1";

	public RTPSocket (InetAddress IP, int UDPport, ConcurrentLinkedQueue<byte[]> dataInQueue, ConcurrentLinkedQueue<byte[]> dataOutQueue, long maxRcvWinSize, long peerWinSize) {
		this(IP, UDPport);
		this.dataInQueue = dataInQueue;
		this.dataOutQueue = dataOutQueue;
		this.maxRcvWinSize = maxRcvWinSize;
		rcvWinSize = maxRcvWinSize;
		this.peerWinSize = peerWinSize;
		seqNum = 0;
		highestSeqNumGivenToApplication = -1;
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
		final int DATA_PER_PACKET = 500;
		for (int offset = 0; offset < sendBytes.length; offset += DATA_PER_PACKET) {
			int length = offset + DATA_PER_PACKET <= sendBytes.length ? DATA_PER_PACKET : sendBytes.length - offset; //how much data will be copied from sendBytes
			byte[] dataToSend = new byte[length];
			System.arraycopy(sendBytes, offset, dataToSend, 0, length);
			dataOutQueue.add(dataToSend);
		}
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
	protected void updateDataToAppQueue() {
		long seqNum = highestSeqNumGivenToApplication + 1;
		boolean miss = false;
		while (!miss) { //while the packet with the next seqNum can be found in the buffer:
			for (JSONObject packet: bufferList) {
				if ((Long) packet.get("seqNum") == seqNum) {
					String dataStr = (String) packet.get("data");
					byte[] dataBytes = null;
					try {
						dataBytes = dataStr.getBytes(ENCODING);
					} catch (UnsupportedEncodingException e) {
						System.out.println("unsupported encoding");
						System.exit(-1);
					}
					dataInQueue.add(dataBytes);
					bufferList.remove(packet);
					highestSeqNumGivenToApplication = seqNum;
					miss = false;
					System.out.println("added seqNum " + seqNum + " to application in queue with size " + dataBytes.length);
					continue;
				}
			}
			miss = true;
		}
	}
}