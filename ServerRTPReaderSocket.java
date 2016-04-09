import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerRTPReaderSocket {
	protected InetAddress IP;
	protected int UDPport;
	

	public ServerRTPReaderSocket(InetAddress IP, int UDPport) {
		this.IP = IP;
		this.UDPport = UDPport;
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