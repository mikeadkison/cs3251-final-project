import java.util.*;

import java.io.*;

public class ServerTCP {
	private static final Map<Integer, Map<String, Object>> ID_TO_INFO_MAP = new HashMap<>(); //contains the information of all the students in the "database"
	private static final String ENCODING = "ISO-8859-1";
	private static byte newLineByte;

	public ServerTCP(int port) throws IOException, IllegalArgumentException {
		System.out.println("creating server");
		try {
			newLineByte = "\n".getBytes(ENCODING)[0];
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		putStubData();

		ServerRTPSocket serverSocket = new ServerRTPSocket(port, 1000);
		byte[] requestBuffer = new byte[0];

		//main server loop
		while (true) {
			RTPSocket activeSocket = serverSocket.accept(); //accept a connection on the passive socket and create a corresponding active socket for data transfer
			System.out.println("client connected");


			/*
			 * REQUEST FORMAT: xxxxxxxxx:column_name0:column_name1: ... column_namen
			 *               id^^^^^^^^^
			 */
			boolean requestFound = false;
			String request = null;  //get the raw request from the client
			while (!requestFound) {
				byte[] readBytes = activeSocket.read();
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
							request = new String(requestBytes, ENCODING);
						} catch (UnsupportedEncodingException e) {
							e.printStackTrace();
						}
						requestFound = true;
					}
				}
			}

			System.out.println("request: " + request);
			String[] requestSplit = request.split(":"); //begin parsing the request
			Integer id = Integer.parseInt(requestSplit[0]); //id of the student whose information is being requested



			/*
			 * RESPONSE FORMAT:
			 * first_requested_column_value:second_requested_column_value ..., :nth_requested_column_value:
			 */
			//construct the response, which is either the requested information or an error
			String response = "RESPONSE:";
			Map<String, Object> values = ID_TO_INFO_MAP.get(id); //get the values for specified player id
			 

			//construct response and possibly send errors

			if (null == values) {
				response = "ERROR: student id not found"; //this student's information does not exist in the system
			} else {
				//iteerate thru columns in request
				for (int i = 1; i < requestSplit.length; i++) {
					Object value = values.get(requestSplit[i]);
					if (null == value) {
						response = "ERROR: column value " + requestSplit[i] + " not found"; //this column does not exist in the system
						break;
					}
					response += value.toString() + ":";
				}
			}
			response += "\n";
			
			try {
				activeSocket.send(response.getBytes(ENCODING)); //send response
			} catch (UnsupportedEncodingException e) {
				System.out.println("unsupported encoding");
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


	/**
	  * create data used to simulate relational database
	  */
	private void putStubData() {
		Map<String, Object> infoMap = new HashMap<>();
		infoMap.put("first_name", "Anthony");
		infoMap.put("last_name", "Peterson");
		infoMap.put("quality_points", 231);
		infoMap.put("gpa_hours", 63);
		infoMap.put("gpa", 3.666667);
		infoMap.put("ID", 903076259);
		ID_TO_INFO_MAP.put(903076259, infoMap);


		infoMap = new HashMap<>();
		infoMap.put("first_name", "Richard");
		infoMap.put("last_name", "Harris");
		infoMap.put("quality_points", 236);
		infoMap.put("gpa_hours", 66);
		infoMap.put("gpa", 3.575758);
		infoMap.put("ID", 903084074);
		ID_TO_INFO_MAP.put(903084074, infoMap);

		infoMap = new HashMap<>();
		infoMap.put("first_name", "Joe");
		infoMap.put("last_name", "Miller");
		infoMap.put("quality_points", 224);
		infoMap.put("gpa_hours", 65);
		infoMap.put("gpa", 3.446154);
		infoMap.put("ID", 903077650);
		ID_TO_INFO_MAP.put(903077650, infoMap);


		infoMap = new HashMap<>();
		infoMap.put("first_name", "Todd");
		infoMap.put("last_name", "Collins");
		infoMap.put("quality_points", 218);
		infoMap.put("gpa_hours", 56);
		infoMap.put("gpa", 3.892857);
		infoMap.put("ID", 903083691);
		ID_TO_INFO_MAP.put(903083691, infoMap);

		infoMap = new HashMap<>();
		infoMap.put("first_name", "Laura");
		infoMap.put("last_name", "Stewart");
		infoMap.put("quality_points", 207);
		infoMap.put("gpa_hours", 64);
		infoMap.put("gpa", 3.234375);
		infoMap.put("ID", 903082265);
		ID_TO_INFO_MAP.put(903082265, infoMap);

		infoMap = new HashMap<>();
		infoMap.put("first_name", "Marie");
		infoMap.put("last_name", "Cox");
		infoMap.put("quality_points", 246);
		infoMap.put("gpa_hours", 63);
		infoMap.put("gpa", 3.904762);
		infoMap.put("ID", 903075951);
		ID_TO_INFO_MAP.put(903075951, infoMap);


		infoMap = new HashMap<>();
		infoMap.put("first_name", "Stephen");
		infoMap.put("last_name", "Baker");
		infoMap.put("quality_points", 234);
		infoMap.put("gpa_hours", 66);
		infoMap.put("gpa", 3.545455);
		infoMap.put("ID", 903084336);
		ID_TO_INFO_MAP.put(903084336, infoMap);
	}
}