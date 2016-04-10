import java.io.*;
import java.net.*;
import java.util.*;

public class ServerTester {
	private static final String ENCODING = "ISO-8859-1";

	public static void main(String[] args) {
		ServerRTPSocket serverSocket = new ServerRTPSocket(8081, 5);
		RTPSocket rcvSocket = serverSocket.accept();
		while (true) {
			try {
				byte[] data = rcvSocket.read();
				String str = new String(data, ENCODING);
				if (data.length > 0) {
					System.out.println("read data from api size " + data.length);
					rcvSocket.send("got your data".getBytes(ENCODING));
				}
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
	}
}