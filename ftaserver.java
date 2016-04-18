import org.json.simple.*;
import java.util.*;

import java.io.*;
import java.nio.file.*;
import org.json.simple.*;
/**
 * a wrapper class for running the server from the command line
 * expects the port number as an arg
 */
public class ftaserver {
	public static void main(String[] args) throws UnsupportedEncodingException {
		int port = -1;
		try {
			port = Integer.parseInt(args[0]);
		} catch (Exception e) {
			System.out.println("Please provide a valid port");
		}
		int maxRcvWinSize = Integer.parseInt(args[1]);

		ServerRTPSocket acceptingSocket = new ServerRTPSocket(port, maxRcvWinSize);

		while (true) {
			RTPSocket socket = acceptingSocket.accept();
			FTAServerThread threadForThisClient = new FTAServerThread(socket);
			threadForThisClient.start();
		}
		
	}
}