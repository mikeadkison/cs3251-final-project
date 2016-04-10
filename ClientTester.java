import java.io.*;
import java.net.*;
import java.util.*;

public class ClientTester {
	private static final String BIG_STRING = new String(new char[502]).replace("\0", "-");
	private static final String ENCODING = "ISO-8859-1";

	public static void main(String[] args) {
		InetAddress addr = null;
        try {
            addr = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
		ClientRTPSocket clientSocket = new ClientRTPSocket(addr, 8081);
		try {
			clientSocket.send(BIG_STRING.getBytes(ENCODING));
		} catch (UnsupportedEncodingException e) {
			System.out.println("issue wwith encoding");
		}
	}
}