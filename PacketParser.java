import java.io.*;
import java.nio.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;

/**
 * forms and parses packets
 * packet format: [checksum - 16 bytes][ACK? - 1 byte][seqNum - 4 bytes][data - N bytes]
 */
public class PacketParser {
	private static final int CHECKSUM_SIZE = 16; //length of checksum in bytes
	private static final int SEQNUM_SIZE = 4; //seqnum can be up to 2^32 (4 bytes)
	private static final int FLAG_SIZE = 1; //1 byte for flags

	private byte[] combine(byte[] checksum, byte[] message) {
        byte[] combined = new byte[checksum.length + message.length];
        System.arraycopy(checksum, 0, combined, 0, checksum.length); //place checksum at beginning of packet
        System.arraycopy(message, 0, combined, checksum.length, message.length); //place rest of packet after the checksum
        return combined;
    }

    private byte[] getChecksum(byte[] packet) {
        byte[] checksum = new byte[CHECKSUM_SIZE];
        System.arraycopy(packet, 0, checksum, 0, CHECKSUM_SIZE);
        return checksum;
    }

    protected byte[] getMessage(byte[] packet) {
        int messageSize = packet.length - CHECKSUM_SIZE - SEQNUM_SIZE - FLAG_SIZE;
        byte[] message = new byte[messageSize];
        System.arraycopy(packet, CHECKSUM_SIZE + SEQNUM_SIZE + FLAG_SIZE, message, 0, messageSize);
        return message;
    }

    protected int getSeqNum(byte[] packet) {
    	return ByteBuffer.wrap(packet, CHECKSUM_SIZE + FLAG_SIZE, SEQNUM_SIZE).getInt();
    }

    private byte[] intToBytes(int integer) {
    	return ByteBuffer.allocate(4).putInt(integer).array();
    }

    protected byte[] constructPacket(byte[] data, boolean isAck, int seqNum) {
    	byte[] packetWithoutChecksum = new byte[FLAG_SIZE + SEQNUM_SIZE + data.length];
    	byte flagByte = isAck ? (byte) 1 : (byte) 0;
    	packetWithoutChecksum[0] = flagByte; //put flags in packet
    	byte[] seqNumBytes = intToBytes(seqNum);
    	System.arraycopy(seqNumBytes, 0, packetWithoutChecksum, FLAG_SIZE, seqNumBytes.length); //put seqNum in packet
    	System.arraycopy(data, 0, packetWithoutChecksum, FLAG_SIZE + SEQNUM_SIZE, data.length); //put data in packet
    	byte[] checksum = checksum(packetWithoutChecksum);
    	return combine(checksum, packetWithoutChecksum); //return the full packet (includes the checksum)
    }

    /**
     * @return 16 byte MD5 checksum of given bytes
     */
    private byte[] checksum(byte[] messageBytes) {
        MessageDigest msgDigest = null;
        try {
            msgDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.println("md5 not available for checksum");
            System.exit(-1);
        }

        msgDigest.update(messageBytes);
        return msgDigest.digest();
    }
}