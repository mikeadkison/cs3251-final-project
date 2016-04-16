import java.io.*;
import java.net.*;
import java.util.*;

public class ServerTester {
	private static final String ENCODING = "ISO-8859-1";

	public static void main(String[] args) {
		ServerRTPSocket serverSocket = new ServerRTPSocket(8081, 4000);
		RTPSocket rcvSocket = serverSocket.accept();
		while (true) {
			try {
				byte[] data = rcvSocket.read();
				String str = new String(data, ENCODING);
				if (data.length > 0) {
					System.out.println("READ DATA FROM API SIZE " + data.length);
					rcvSocket.send("hellos".getBytes(ENCODING));
				}
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
	}
}