import java.net.InetAddress;
import java.util.List;
import java.util.ArrayList;

import java.io.IOException;

import java.net.UnknownHostException;

/**
 *	a wrapper class for running the client
 * expects arguments in the form ip:port student_id requested colummns
 */
public class dbclientTCP {
	public static void main(String[] args) {
		InetAddress serverAddress = null;

		try {
			serverAddress = InetAddress.getByName(args[0].split(":")[0]);
		} catch (UnknownHostException e) {
			System.out.println("issue with server address");
			System.out.println(args[0].split(":")[0]);
			e.printStackTrace();
		}
		int port = Integer.parseInt(args[0].split(":")[1]);
		String studentID = args[1]; // the ID of the student whose information we are requesting
		List<String> columnNames = new ArrayList<>(); //will contain a list of requested columns
		for (int i = 2; i < args.length; i++) {
			columnNames.add(args[i]);
		}

		try {
			ClientTCP client = new ClientTCP(serverAddress, studentID, columnNames, port); //start the client
		} catch (IOException e) {
			System.out.println("client could not connect to server, try checking arguments, make sure server is running");
		}
		
	}
}