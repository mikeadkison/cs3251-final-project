import java.io.*;
import java.net.*;
import java.util.*;

public class ServerTester {
	private static final String ENCODING = "ISO-8859-1";

	public static void main(String[] args) {
		ServerRTPSocket serverSocket = new ServerRTPSocket(8081, 1000);
		//RTPSocket rcvSocket = serverSocket.accept();
		while (true) {
			try {
				System.out.println(Arrays.toString("\n".getBytes(ENCODING)));
				/*byte[] data = rcvSocket.read();
				String str = new String(data, ENCODING);
				if (data.length > 0) {
					System.out.println("READ DATA FROM API SIZE " + new String(data, ENCODING));
					rcvSocket.send("hellos".getBytes(ENCODING));
				}*/
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
	}
}