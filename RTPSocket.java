import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

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

	public RTPSocket (InetAddress IP, int UDPport, ConcurrentLinkedQueue<byte[]> dataInQueue, ConcurrentLinkedQueue<byte[]> dataOutQueue, long maxRcvWinSize, long peerWinSize) {
		this(IP, UDPport);
		this.dataInQueue = dataInQueue;
		this.dataOutQueue = dataOutQueue;
		this.maxRcvWinSize = maxRcvWinSize;
		rcvWinSize = maxRcvWinSize;
		this.peerWinSize = peerWinSize;
		seqNum = 0;
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
}