package p1;

public class Frame {
	public static final byte ACK_TIMEOUT = 0x00;
	public static final byte ACK_CRC_ERR = 0x01;
	public static final byte ACK_FIREWALL = 0x10;
	public static final byte ACK_ok = 0x11;
	
	private final byte src;
	private final byte dst;
	private final byte sizeOrAck;
	private final byte ackType;
	private final byte[] data;
	private final byte crc;
	
	// Creates a data frame with a message
	public static Frame dataFrame(int src, int dst, byte[] payload) {
		if(payload == null) {
			throw new IllegalArgumentException("message cannot be null");
		}
		if(payload.length == 0 || payload.length > 255) {
			throw new IllegalArgumentException("message length must be 1 to 255");
		}
		
		byte s = (byte) (src & 0xFF);
		byte d = (byte) (dst & 0xFF);
		byte size = (byte) (payload.length & 0xFF);
		
		return new Frame(s, d, size, (byte) 0x00, payload, (byte) 0x00);
	}
	
	// Creates acknowledgement frame
	public static Frame ackFrame(int src, int dst, byte ackType) {
		byte s = (byte) (src & 0xFF);
		byte d = (byte) (dst & 0xFF);
		byte size = 0;
		
		return new Frame(s, d, size, ackType, new byte[0], (byte) 0x00);
	}
	
	private Frame(byte src, byte dst, byte sizeOrAck, byte ackType, byte[] data, byte crc) {		
		this.src = src;
		this.dst = dst;
		this.sizeOrAck = sizeOrAck;
		this.ackType = ackType;
		this.data = (data == null) ? new byte[0] : data;
		this.crc = crc;
	}
	
	// Getters
	public int getSrc() {
		return src & 0xFF;
	}
	
	public int getDst() {
		return dst & 0xFF;
	}
	
	public int getSizeOrAck() {
		return sizeOrAck & 0xFF;
	}
	
	public byte getAckType() {
		return ackType;
	}
	
	public byte[] getData() {
		return data.clone();
	}
	public byte getCrc() {
		return crc;
	}
	
	public boolean isAck() {
		return (sizeOrAck & 0xFF) == 0;
	}
	
	public int getDataLength() {
		return sizeOrAck & 0xFF;
	}
	
	// Converts frame into byte array to send over a socket
	public byte[] toBytes() {
		int len = 5 + data.length;
		byte[] buf = new byte[len];
		
		buf[0] = src;
		buf[1] = dst;
		buf[2] = 0x00;
		buf[3] = sizeOrAck;
		buf[4] = ackType;
		System.arraycopy(data, 0, buf, 5, data.length);
		
		byte crcValue = computeCrc(buf);
		buf[2] = crcValue;
		
		return buf;
	}
	
	// Computes the CRC
	private static byte computeCrc(byte[] buf) {
		int sum = 0;
		for(byte b : buf) {
			sum += (b & 0xFF);
		}
		return (byte) (sum & 0xFF);
	}
	
	// Checks if CRC is valid
	public boolean isCrcValid() {
		int len = 5 + data.length;
		byte[] buf = new byte[len];
		
		buf[0] = src;
		buf[1] = dst;
		buf[2] = 0x00;
		buf[3] = sizeOrAck;
		buf[4] = ackType;
		System.arraycopy(data, 0, buf, 5, data.length);
		
		byte recomputed = computeCrc(buf);
		return recomputed == crc;
	}
	
	// First 3 bytes represent source id, destination id, and data length
	public static Frame fromBytes(byte[] buf) {
		if(buf == null || buf.length < 5) {
			throw new IllegalArgumentException("Frame too short");
		}
		
		byte src = buf[0];
		byte dst = buf[1];
		byte crc = buf[2];
		byte sizeOrAck = buf[3];
		byte ackType = buf[4];
		
		int size = sizeOrAck & 0xFF;
		int expectedLen = 5 + (size > 0 ? size : 0);
		if(buf.length != expectedLen) {
			throw new IllegalArgumentException("Frame length mismatch: expected " + expectedLen + " but got " + buf.length);
		}
		
		byte[] data = new byte[size > 0 ? size : 0];
		if(size > 0) {
			System.arraycopy(buf, 5, data, 0, data.length);
		}
		
		return new Frame(src, dst, sizeOrAck, ackType, data, crc);
	}
	
	// For output log
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Frame[dst=").append(getDst()).append(", src=").append(getSrc()).append(", sizeOrAck=").append(getSizeOrAck()).append(", ackType=0x").append(Integer.toHexString(ackType & 0xFF)).append(", crc=0x").append(Integer.toHexString(crc & 0xFF)).append(", dataLen=").append(data.length).append("]");
		return sb.toString();
	}
}
