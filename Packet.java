import java.io.*;
import java.nio.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;

/**
 * represents a packet
 * packet byte format: [checksum - 16 bytes][packet size - 2 bytes][FLAG NUMBER? - 1 byte][seqNum - 4 bytes][data - N bytes]
 *
 * FLAGS: name  #
 *        ACK | 1
 *        connectionInit | 2 // first of 3 way handshake
 *        connectionInitConfirm | 3 // second of 3 way handshake
 *        connectionInitConfirmAck | 4 //third of 3 way handshake
 * the checksum does not not include the packet size 
 */
public class Packet {
	protected boolean isAck;
	protected byte[] data;
	protected byte[] checksum;
	protected int seqNum;
	protected byte[] packetBytes; //packet in byte form for sending over network
	protected boolean checksumMatch;
	protected byte flag;

	private static final int PACKET_SIZE_SIZE = 2; //packet size is specified in 2 bytes(uint16). the packet size includes the packet size bytes
	private static final int CHECKSUM_SIZE = 16; //length of checksum in bytes
	private static final int SEQNUM_SIZE = 4; //seqnum can be up to 2^32 (4 bytes)
	private static final int FLAG_SIZE = 1; //1 byte for flags

	protected static final byte DATA = (byte) 0;
	protected static final byte ACK = (byte) 1;
	protected static final byte CONNECTION_INIT = (byte) 2;
	protected static final byte CONNECTION_INIT_CONFIRM = (byte) 3;
	protected static final byte CONNECTION_INIT_CONFIRM_ACK = (byte) 4;

	/**
	 * used to construct a packet from received bytes
	 */
	public Packet(byte[] bufferBytes) {
		int packetSize = getPacketSize(bufferBytes); //first, cut off the part of the buffer that is free space, not actually being used to hold the packet
		byte[] packetBytes = new byte[packetSize];
		System.arraycopy(bufferBytes, 0, packetBytes, 0, packetSize);

		byte[] checksumBytesFromPacket = getChecksum(packetBytes); //extract the checksum from the received packet
		byte[] packetWithoutChecksum = withoutChecksum(packetBytes); //remove the checksum bytes from the received packet
		byte[] checksumBytesCalc = getChecksum(packetWithoutChecksum);
		if (!Arrays.equals(checksumBytesFromPacket, checksumBytesCalc)) { //make sure checksum matches rest of packet
			checksumMatch = false;
		} else {
			checksumMatch = true;
		}
		data = getMessage(packetBytes);
		checksum = checksumBytesFromPacket;
		byte flagByte = getFlagByte(packetBytes);
		isAck = flagByte == ACK ? true : false;

	}

	/**
	 * used to construct a packet whose bytes you plan to send out
	 */
	public Packet(byte[] data, byte flag, int seqNum) {
		packetBytes = constructPacket(data, flag, seqNum);
		this.data = data;
		this.isAck = flag == (byte) 1 ? true: false;
		this.seqNum = seqNum;
		this.flag = flag;
		checksumMatch = true;
	}

	 /** FLAGS: name  #
	 *        ACK | 1
	 *        connectionInit | 2 // first of 3 way handshake
	 *        connectionInitConfirm | 3 // second of 3 way handshake
	 *        connectionInitConfirmAck | 4 //third of 3 way handshake
	 */
	private boolean isAck() {
		return flag == ACK;
	}

	protected boolean isConnectionInit() {
		return flag == CONNECTION_INIT;
	}

	protected boolean isConnectionInitConfirm() {
		return flag == CONNECTION_INIT_CONFIRM;
	}

	protected boolean isConnectionInitConfirmAck() {
		return flag == CONNECTION_INIT_CONFIRM_ACK;
	}

	protected int getPacketSize(byte[] buffer) {
		byte[] sizeBytes = new byte[PACKET_SIZE_SIZE];
		System.arraycopy(buffer, CHECKSUM_SIZE, sizeBytes, 0, PACKET_SIZE_SIZE);
		return ByteBuffer.wrap(sizeBytes).getChar();
	}

	// get the bytes of the packet to send over the network
	protected byte[] getBytes() {
		return packetBytes;
	}

	private byte getFlagByte(byte[] packetBytes) {
		return packetBytes[CHECKSUM_SIZE + PACKET_SIZE_SIZE]; //the flag byte is right after the bytes which specify packet size
	}

	private byte[] withoutChecksum(byte[] packetBytes) {
		byte[] withoutChecksum = new byte[packetBytes.length - CHECKSUM_SIZE];
		System.arraycopy(packetBytes, CHECKSUM_SIZE, withoutChecksum, 0, withoutChecksum.length);
		return withoutChecksum;
	}

	private byte[] combine(byte[] firstArray, byte[] secondArray) {
        byte[] combined = new byte[firstArray.length + secondArray.length];
        System.arraycopy(firstArray, 0, combined, 0, firstArray.length); //place firstArray at beginning
        System.arraycopy(secondArray, 0, combined, firstArray.length, secondArray.length); //place second array at end
        return combined;
    }

    private byte[] getChecksum(byte[] packet) {
        byte[] checksum = new byte[CHECKSUM_SIZE];
        System.arraycopy(packet, 0, checksum, 0, CHECKSUM_SIZE);
        return checksum;
    }

    protected byte[] getMessage(byte[] packet) {
        int messageSize = packet.length - CHECKSUM_SIZE - PACKET_SIZE_SIZE - SEQNUM_SIZE - FLAG_SIZE; //the message size = packet size - metadata size
        byte[] message = new byte[messageSize];
        System.arraycopy(packet, CHECKSUM_SIZE + PACKET_SIZE_SIZE + SEQNUM_SIZE + FLAG_SIZE, message, 0, messageSize);
        return message;
    }

    protected int getSeqNum(byte[] packet) {
    	return ByteBuffer.wrap(packet, CHECKSUM_SIZE + PACKET_SIZE_SIZE + FLAG_SIZE, SEQNUM_SIZE).getInt();
    }

    private byte[] intToBytes(int integer) {
    	return ByteBuffer.allocate(4).putInt(integer).array();
    }

    protected byte[] constructPacket(byte[] data, byte flagByte, int seqNum) {
    	byte[] packetWithoutChecksum = new byte[PACKET_SIZE_SIZE + FLAG_SIZE + SEQNUM_SIZE + data.length];

    	char packetSize = (char) (CHECKSUM_SIZE + PACKET_SIZE_SIZE + FLAG_SIZE + SEQNUM_SIZE + data.length); //the size in bytes of the packet
    	byte[] packetSizeBytes = charToBytes(packetSize);
    	System.arraycopy(packetSizeBytes, 0, packetWithoutChecksum, 0, PACKET_SIZE_SIZE); //put packet size in the packet size bytes

    	packetWithoutChecksum[PACKET_SIZE_SIZE] = flagByte; //put flags in packet after the packet size bytes

    	byte[] seqNumBytes = intToBytes(seqNum);
    	System.arraycopy(seqNumBytes, 0, packetWithoutChecksum, PACKET_SIZE_SIZE + FLAG_SIZE, seqNumBytes.length); //put seqNum in packet

    	System.arraycopy(data, 0, packetWithoutChecksum, PACKET_SIZE_SIZE + FLAG_SIZE + SEQNUM_SIZE, data.length); //put data in packet

    	byte[] checksum = checksum(packetWithoutChecksum);
    	byte[] withChecksum = combine(checksum, packetWithoutChecksum); //return the full packet (includes the checksum)

    	return withChecksum;
    }

    private byte[] charToBytes(char character) {
    	return ByteBuffer.allocate(2).putChar(character).array();
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

    @Override
    public String toString() {
    	return "ACK? : " + isAck + " Checksum: " + Arrays.toString(checksum) + " data length: " + data.length;
    }

    @Override
    public boolean equals(Object other) {
    	if (this == other) {
			return true;
		}
		if (!(other instanceof Packet)) {
			return false;
		}
		final Packet theOther = (Packet) other;
		return this.seqNum == theOther.seqNum && Arrays.equals(this.checksum, theOther.checksum);
    }
}