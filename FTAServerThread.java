import org.json.simple.*;
import java.util.*;

import java.io.*;
import java.nio.file.*;
import org.json.simple.*;

public class FTAServerThread extends Thread {
	private RTPSocket socket;
	private static final String ENCODING = "ISO-8859-1";
	private static byte newLineByte;

	public FTAServerThread(RTPSocket socket) throws UnsupportedEncodingException {
		this.socket = socket;
		this.newLineByte = "\n".getBytes(ENCODING)[0];
	}

	@Override
	public void run() {
		/*
		 * receive a json message with information about the request
		 */

		byte[] requestBuffer = new byte[0];

		while (true) {

			boolean requestFound = false;
			String requestStr = null;  //get the raw request from the client

			while (!requestFound) {
				byte[] readBytes = socket.read();
				if (readBytes.length > 0) {
					byte[] streamBuffer = new byte[requestBuffer.length + readBytes.length];
					System.arraycopy(requestBuffer, 0, streamBuffer, 0, requestBuffer.length);
					System.arraycopy(readBytes, 0, streamBuffer, requestBuffer.length, readBytes.length);

					int separatorIndex = getFirstIndexOf(newLineByte, streamBuffer);
					if (separatorIndex >= 0) { //the there is an end of a message
						byte[] requestBytes = new byte[separatorIndex];
						System.arraycopy(streamBuffer, 0, requestBytes, 0, requestBytes.length);

						requestBuffer = new byte[streamBuffer.length - 1 - requestBytes.length];
						System.arraycopy(streamBuffer, separatorIndex + 1, requestBuffer, 0, requestBuffer.length);
						try {
							requestStr = new String(requestBytes, ENCODING);
						} catch (UnsupportedEncodingException e) {
							e.printStackTrace();
						}
						requestFound = true;
					}
				}
			}

			System.out.println("request: " + requestStr);
			JSONObject request = (JSONObject) JSONValue.parse(requestStr);
			if (request.get("type").equals("get")) {
				Path filePath = Paths.get((String) request.get("fileName"));
				byte[] fileBytes = null;
				try {
					fileBytes = Files.readAllBytes(filePath);
				} catch (IOException e) {
					System.out.println("issue reading file");
				}
				System.out.print("file size in bytes: " + fileBytes.length);
				socket.send(fileBytes);
			}
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