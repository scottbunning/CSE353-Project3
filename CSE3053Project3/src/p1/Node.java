package p1;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class Node implements Runnable{
	private int networkId;
	private int localId;
	private int nodeId;
	private String host;
	private int port;
	
	private Socket socket;
	private DataInputStream in;
	private DataOutputStream out;
	
	private boolean running = true;
	private final Random rand = new Random();
	
	private BufferedWriter outputWriter;
	
	private final Object ackLock = new Object();
	private boolean waitingForAck = false;
	private boolean ackReceived = false;
	private byte lastAckType = 0;
	private int lastAckFrom = -1;
	
	private static final int MAX_RETRIES = 3;
	private static final long ACK_TIMEOUT_MS = 500;
	
	public Node(int networkId, int localId, String host, int port) {
		this.networkId = networkId;
		this.localId = localId;
		this.nodeId = (networkId * 16) + localId;
		this.host = host;
		this.port = port;
	}
	
	@Override
	public void run() {
	    Thread listener = null;
	    try {
	        connectToSwitch();
	        setupOutputFile();

	        // Start listener thread to receive frames
	        listener = new Thread(this::listenForFrames, "Node-" + nodeId + "-listener");
	        listener.start();

	        // Send everything in nodeX.txt
	        sendFromInputFile();

	        // Give some time for ACKs / remaining frames
	        try {
	            Thread.sleep(500);
	        } catch (InterruptedException ignored) {
	            Thread.currentThread().interrupt();
	        }

	        running = false;

	        try {
	            if (socket != null && !socket.isClosed()) {
	                socket.close();
	            }
	        } catch (IOException ignored) {
	        }

	        // Wait for listener thread to finish
	        if (listener != null) {
	            try {
	                listener.join();
	            } catch (InterruptedException e) {
	                Thread.currentThread().interrupt();
	                System.err.println(tag() + "Interrupted while waiting for listener to stop.");
	            }
	        }

	    } catch (Exception e) {
	        System.err.println(tag() + "Error: " + e.getMessage());
	        e.printStackTrace();
	    } finally {
	        cleanup();  // close outputWriter, etc.
	    }
	}
	
	// Connects to switch and retries every 500ms until it's successful
	private void connectToSwitch() throws IOException {
		// make sure output file exists
		while(true) {
			try {
				socket = new Socket(host, port);
				in = new DataInputStream(socket.getInputStream());
				out = new DataOutputStream(socket.getOutputStream());
				System.out.println(tag() + "Connected to switch " + host + ":" + port);
				break;
			} catch(IOException e) {
				System.out.println(tag() + "Switch not ready. Will retry.");
				try {
					Thread.sleep(500);
				} catch(InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for switch", ie);
				}
			}
		}
		
	}
	
	// Sets up output file
	private void setupOutputFile() throws IOException {
		String outFileName = "node" + networkId + "_" + localId + "output.txt";
		outputWriter = new BufferedWriter(new FileWriter(outFileName));
	}
	
	// Listens for new frames
	// Writes the received data to node#output.txt
    private void listenForFrames() {
        try {
            while(running) {
                int len;
                try {
                    len = in.readUnsignedShort();
                } catch (EOFException e) {
                    break;
                }

                byte[] buf = new byte[len];
                in.readFully(buf);

                Frame frame = Frame.fromBytes(buf);

                if (frame.isAck()) {
                    handleAck(frame);
                } else {
                    handleDataFrame(frame);
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Node " + nodeId + " receive error");
                e.printStackTrace();
            }
        }
    }

	// Reads node#_#.txt and sends each line as a data frame
    private void sendFromInputFile() {
        String inFileName = "node" + networkId + "_" + localId + ".txt";

        try (BufferedReader r = new BufferedReader(new FileReader(inFileName))) {
            String line;
            while ((line = r.readLine()) != null && running) {
                line = line.trim();
                if (line.isEmpty()) {
                    // skip blank lines
                    continue;
                }

                String[] parts = line.split(":", 2);
                if (parts.length < 2) {
                    System.err.println(tag() + "Skipping bad line (no ':'): " + line);
                    continue;
                }

                String dstPart = parts[0].trim();
                String message = parts[1].trim();

                if (message.isEmpty()) {
                    System.err.println(tag() + "Skipping empty message to " + dstPart);
                    continue;
                }
                
                String[] dstPieces = dstPart.split("_", 2);
                if(dstPieces.length < 2) {
                	System.err.println(tag() + "Bad format (no '_'): " + dstPart);
                	continue;
                }

                int dstNet, dstLocal;
                try {
                    dstNet = Integer.parseInt(dstPieces[0]);
                    dstLocal = Integer.parseInt(dstPieces[1]);
                } catch (NumberFormatException e) {
                    System.err.println(tag() + "Bad destination numbers in: " + dstPart);
                    continue;
                }
                
                int dstId = dstNet * 16 + dstLocal;

                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                if (payload.length == 0 || payload.length > 255) {
                    System.err.println(tag() + "Skipping line, payload length " +
                            payload.length + " is out of range 1..255: " + line);
                    continue;
                }

               sendWithRetries(dstId, payload);
            }
        } catch (IOException e) {
            System.err.println(tag() + "Send error: " + e.getMessage());
        }
    }
	
	// Sends frame over the socket and converts frame to bytes
	private void sendFrame(Frame frame) throws IOException {
		byte[] receivedBytes = frame.toBytes();
		
		if(frame.isAck()) {
			if(rand.nextDouble() < 0.05) {
				System.out.println(tag() + "Dropping ACK to " + frame.getDst());
				return;
			}
		} else {
			if(rand.nextDouble() < 0.05) {
				int idx = rand.nextInt(receivedBytes.length);
				receivedBytes[idx] ^= 0x01;
				System.out.println(tag() + "Corrupted DATA frame to " + frame.getDst() + " at byte index " + idx);
			}
		}
		
		out.writeShort(receivedBytes.length);
		out.write(receivedBytes);
		out.flush();
		
		if(frame.isAck()) {
			System.out.println(tag() + "SENT ACK to " + frame.getDst() + " type=0x" + Integer.toHexString(frame.getAckType() & 0xFF));
		} else {
			System.out.println(tag() + "SENT DATA to " + frame.getDst() + " dataLen=" + frame.getDataLength());
		}
	}
	
	// Sends with a certain amount of retries if fails
	private void sendWithRetries(int dst, byte[] payload) throws IOException {
		int attempt = 0;
		
		while(attempt < MAX_RETRIES) {
			attempt++;
			
			Frame frame = Frame.dataFrame(nodeId, dst, payload);
			
			synchronized(ackLock) {
				waitingForAck = true;
				ackReceived = false;
				lastAckType = 0;
				lastAckFrom = -1;
			}
			
			sendFrame(frame);
			
			long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
			
			synchronized(ackLock) {
				while(!ackReceived && System.currentTimeMillis() < deadline) {
					long remaining = deadline - System.currentTimeMillis();
					if(remaining <= 0) break;
					try {
						ackLock.wait(remaining);
					} catch(InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
				
				waitingForAck = false;
				
				if(!ackReceived) {
					System.out.println(tag() + "Timeout waiting for ACK from " + dst + " attempt " + attempt);
				} else {
					if(lastAckType == Frame.ACK_ok) {
						System.out.println(tag() + "Got positive ACK from " + lastAckFrom + " after " + attempt + " attempts");
						return;
					} else if(lastAckType == Frame.ACK_FIREWALL) {
						System.out.println(tag() + "Got firewall ACK from " + lastAckFrom + " no resend");
						return;
					} else if(lastAckType == Frame.ACK_CRC_ERR) {
						System.out.println(tag() + "Got CRC error ACK from " + lastAckFrom + " on attempt " + attempt + " will retry");
					} else if(lastAckType == Frame.ACK_TIMEOUT) {
						System.out.println(tag() + "Got Timeout ACK from " + lastAckFrom + " on attempt " + attempt + " will retry");
					} else {
						System.out.println(tag() + "Got unknown ACK type 0x" + Integer.toHexString(lastAckType & 0xFF) + " from " + lastAckFrom + ", will retry");
					}
				}
			}
		}
		
		System.out.println(tag() + "Failed to deliver to " + dst + " after " + MAX_RETRIES + "attempts");
	}
	
	// Handles acknowledgement
	private void handleAck(Frame frame) {
		if(frame.getDst() != nodeId) {
			return;
		}
		System.out.println("Node " + nodeId + " RECIEVED ACK from " + frame.getSrc() + "type-0x" + Integer.toHexString(frame.getAckType() & 0xFF));
		
		synchronized(ackLock) {
			if(waitingForAck) {
				ackReceived = true;
				lastAckType = frame.getAckType();
				lastAckFrom = frame.getSrc();
				ackLock.notifyAll();
			}
		}
	}
	
	private void handleDataFrame(Frame frame) throws IOException  {
		if(frame.getDst() != nodeId) {
			return;
		}
		
		if(!frame.isCrcValid()) {
			System.err.println(tag() + "CRC error on frame from " + frame.getSrc());
			Frame noAck = Frame.ackFrame(nodeId, frame.getSrc(), Frame.ACK_CRC_ERR);
			sendFrame(noAck);
			return;
		}
		
		String message = new String(frame.getData(), StandardCharsets.UTF_8);
		
		int srcId = frame.getSrc();
		int srcNet = srcId / 16;
		int srcLocal = srcId % 16;
		
		outputWriter.write(srcNet + "_" + srcLocal + ": " + message);
		outputWriter.newLine();
		outputWriter.flush();
		
		System.out.println(tag() + " RECIEVED from " + srcNet + "_" + srcLocal + "msg=\"" + message + "\"");
		
		Frame ack = Frame.ackFrame(nodeId, frame.getSrc(), Frame.ACK_ok);
		sendFrame(ack);
	}
	
	// Closes the input/output and socket connection
	private void cleanup() {
		try {
			if(outputWriter != null) outputWriter.close();
			if(socket != null) socket.close();
			System.out.println("Node " + nodeId + " shutdown complete ");
		} catch(IOException ignored) {
			
		}
	}
	
	// Helper for log output
	private String tag() {
		return "[Node" + networkId + "_" + localId + "]";
	}
}
