import java.util.List;
import java.util.ArrayList;

import java.io.*;
import java.util.*;
import java.net.*;

public class ClientTCP {
	private static final String ENCODING = "ISO-8859-1";
	private static byte newLineByte;

	public ClientTCP(InetAddress serverIP, String studentID, List<String> requestedColumns, int port) throws IOException {
		ClientRTPSocket clientRTPSocket = new ClientRTPSocket(serverIP, port, 10000);
		System.out.println("hi");
		RTPSocket socket = clientRTPSocket.connect();
		System.out.println("bye");
		try {
			newLineByte = "\n".getBytes(ENCODING)[0];
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		/*
		 * REQUEST FORMAT: xxxxxxxxxcolumn_name0:column_name1: ... column_namen
		 *               id^^^^^^^^^
		 */
		String request = ""; //formulate the request
		request += studentID + ":";
		for (int i = 0; i < requestedColumns.size(); i++) {
			request += requestedColumns.get(i) + ":";
		}
		request += "\n";

		System.out.println("request which will be sent: " + request);


		try {
			socket.send(request.getBytes(ENCODING)); //send the info request to the server
			System.out.println("sent request");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		//get the response
		byte[] responseBuffer = new byte[0];
		boolean responseFound = false;
		String response = null;

		while (!responseFound) {
			byte[] readBytes = socket.read();
			if (readBytes.length > 0) {
				byte[] streamBuffer = new byte[responseBuffer.length + readBytes.length];
				System.arraycopy(responseBuffer, 0, streamBuffer, 0, responseBuffer.length);
				System.arraycopy(readBytes, 0, streamBuffer, responseBuffer.length, readBytes.length);

				int separatorIndex = getFirstIndexOf(newLineByte, streamBuffer);
				if (separatorIndex >= 0) { //the there is an end of a message
					byte[] responseBytes = new byte[separatorIndex];
					System.arraycopy(streamBuffer, 0, responseBytes, 0, responseBytes.length);

					responseBuffer = new byte[streamBuffer.length - 1 - responseBytes.length];
					System.arraycopy(streamBuffer, separatorIndex + 1, responseBuffer, 0, responseBuffer.length);
					try {
						response = new String(responseBytes, ENCODING);
					} catch (UnsupportedEncodingException e) {
						e.printStackTrace();
					}
					responseFound = true;
				}
			}
		}

		/*
		 * RESPONSE FORMAT:
		 * first_requested_column_value:second_requested_column_value ..., :nth_requested_column_value:
		 */
		String[] responseSplit = response.split(":"); //split the server's response into its individual components
		String responseType = responseSplit[0]; //either ERROR or RESPONSE
		if (responseType.equals("RESPONSE")) {
			//format results to be displayed to user
			String responsePrintout = "From server: ";
			for (int i = 0; i < requestedColumns.size(); i++) { //add information from the requested columns
				responsePrintout += requestedColumns.get(i) + ": ";
				responsePrintout += responseSplit[i + 1];
				if (i < requestedColumns.size() - 1) {
					responsePrintout += ", ";
				}
			}
			System.out.println(responsePrintout);
		} else if (responseType.equals("ERROR")) {
			System.out.println(responseSplit[1]); //print out the error message we got from server
		}
		
		//close the socket

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