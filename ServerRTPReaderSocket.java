import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerRTPReaderSocket {
	protected InetAddress IP;
	protected int UDPport;
	private ConcurrentLinkedQueue<byte[]> dataQueue;

	public ServerRTPReaderSocket(InetAddress IP, int UDPport, ConcurrentLinkedQueue<byte[]> dataQueue) {
		this(IP, UDPport);
		this.dataQueue = dataQueue;
	}

	/**
	 * used for map lookup by server
	 */
	public ServerRTPReaderSocket(InetAddress IP, int UDPport) {
		this.IP = IP;
		this.UDPport = UDPport;
	}

	/**
	 * gives the application a byte array of all the data received so far. The data is consumed
	 */
	public byte[] read() {
		Object[] arrays = (Object[]) dataQueue.toArray();

		int totalSize = 0;
		for (int i = 0; i < arrays.length; i++) {
			totalSize += ((byte[]) arrays[i]).length;
		}

		byte[] allDataArr = new byte[totalSize]; //this byte array will contain all of the data from the stream so far and will be returned to the application
		int amtCopiedSoFar = 0;
		for (int i = 0; i < arrays.length; i++) {
			System.arraycopy((byte[]) arrays[i], 0, allDataArr, amtCopiedSoFar, ((byte[]) arrays[i]).length);
			amtCopiedSoFar += ((byte[]) arrays[i]).length;
			dataQueue.remove(arrays[i]);
			System.out.println("amtCopiedSoFar: " + amtCopiedSoFar);
		}

		return allDataArr;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ServerRTPReaderSocket)) {
			return false;
		}
		final ServerRTPReaderSocket theOther = (ServerRTPReaderSocket) other;
		return this.IP.equals(theOther.IP) && this.UDPport == theOther.UDPport;
	}

	@Override
	public int hashCode() {
		return this.IP.hashCode() + this.UDPport * 13;
	}
}