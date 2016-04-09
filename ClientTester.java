import java.io.*;
import java.net.*;
import java.util.*;

public class ClientTester {
	public static void main(String[] args) {
		InetAddress addr = null;
        try {
            addr = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
		ClientRTPSocket clientSocket = new ClientRTPSocket(addr, 8081);
	}
}