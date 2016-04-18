import java.net.InetAddress;
import java.util.*;

import java.io.*;
import java.nio.file.*;
import org.json.simple.*;
import java.net.UnknownHostException;

/**
 *	a wrapper class for running the client
 * expects arguments in the form ip:port student_id requested colummns
 */
public class ftaclient {
	private static final String ENCODING = "ISO-8859-1";
	private static RTPSocket socket;

	public static void main(String[] args) throws IOException, UnsupportedEncodingException {
		InetAddress serverAddress = null;

		try {
			serverAddress = InetAddress.getByName(args[0].split(":")[0]);
		} catch (UnknownHostException e) {
			System.out.println("issue with server address");
			System.out.println(args[0].split(":")[0]);
			e.printStackTrace();
		}
		int port = Integer.parseInt(args[0].split(":")[1]);
		int maxRcvWinSize = Integer.parseInt(args[1]);

		ClientRTPSocket clientRTPSocket = new ClientRTPSocket(serverAddress, port, maxRcvWinSize);
		ftaclient.socket = clientRTPSocket.connect();

		while (true) {
			Scanner reader = new Scanner(System.in);
			System.out.println("Ready to receive commands");
			String userInput = reader.nextLine();
			String[] inputMatrix = userInput.split(" ");
			String command = inputMatrix[0];

			if (command.equals("get")) {
				String fileName = inputMatrix[1];

				getFromServer(fileName);
			} else if (command.equals("get-post")) {
				
			} else if (command.equals("disconnect")) {

			}
		}
		
		
	}

	private static void getFromServer(String fileName) throws IOException, UnsupportedEncodingException {
		JSONObject getRequest = new JSONObject();
		getRequest.put("type", "get");
		getRequest.put("fileName", fileName);
		socket.send((getRequest.toString() + "\n").getBytes(ENCODING));

		long timeLastReceivedData = 0;
		boolean receivedSomething = false;
		byte[] fileBytes = new byte[0];
		do {
			byte[] receivedBytes = socket.read();
			if (receivedBytes.length > 0) {
				byte[] newFileBytes = new byte[fileBytes.length + receivedBytes.length];
				System.arraycopy(fileBytes, 0, newFileBytes, 0, fileBytes.length);
				System.arraycopy(receivedBytes, 0, newFileBytes, fileBytes.length, receivedBytes.length);
				fileBytes = newFileBytes;
				timeLastReceivedData = System.currentTimeMillis();
				receivedSomething = true;
				System.out.println("received" + fileBytes.length + " so far");
			}

		} while (!receivedSomething || System.currentTimeMillis() - timeLastReceivedData < 20000); //timeout
		
		FileOutputStream stream = new FileOutputStream("get_" + fileName);
		try {
			stream.write(fileBytes);
		} catch (IOException e) {
			System.out.println("issue writing file");
		} finally {
			stream.close();
		}
	}

	private int getFirstIndexOf(byte toFind, byte[] toFindIn) {
		for (int i = 0; i < toFindIn.length; i++) {
			if (toFind == toFindIn[i]) {
				return i;
			}
		}
		return -1;
	}
}