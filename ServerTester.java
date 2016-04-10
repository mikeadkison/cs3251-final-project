import java.io.*;
import java.net.*;
import java.util.*;

public class ServerTester {
	private static final String ENCODING = "ISO-8859-1";

	public static void main(String[] args) {
		ServerRTPSocket serverSocket = new ServerRTPSocket(8081);
		ServerRTPReaderSocket rcvSocket = serverSocket.accept();
		while (true) {
			try {
				byte[] data = rcvSocket.read();
				if (data.length > 0) {
					System.out.println(new String(rcvSocket.read(), ENCODING));
				}
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
	}
}