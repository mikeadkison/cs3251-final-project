import java.io.*;
import java.net.*;
import java.util.*;

public class ServerTester {
	public static void main(String[] args) {
		ServerRTPSocket serverSocket = new ServerRTPSocket(8081);
		//while (true) {
		ServerRTPReaderSocket rcvSocket = serverSocket.accept();
		//}
	}
}