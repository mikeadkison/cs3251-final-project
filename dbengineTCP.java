import java.io.IOException;

/**
 * a wrapper class for running the server from the command line
 * expects the port number as an arg
 */
public class dbengineTCP {
	public static void main(String[] args) {
		int port = -1;
		try {
			port = Integer.parseInt(args[0]);
		} catch (Exception e) {
			System.out.println("Please provide a valid port");
		}
		
		try {
			ServerTCP server = new ServerTCP(port); //start the server
		} catch (IOException e) {
			System.out.println("issue creating server");
		} catch (IllegalArgumentException e) {
			System.out.println("issue creating server");
		}
		
	}
}