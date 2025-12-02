package p1;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class CASSwitch implements Runnable{
	private final int port;
	private final int networkId;
	private boolean running = true;
	
    private final String ccsHost;
    private final int ccsPort;
    private Socket ccsSocket;
    private DataInputStream ccsIn;
    private DataOutputStream ccsOut;
    private Thread ccsListenerThread;
	
	private Map<Integer, ClientHandler> table = new HashMap<>(); // Switching table
	
	private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
	
	private List<Frame> frameBuffer = new ArrayList<>(); // buffer for storing frames
	
	public CASSwitch(int port) {
		this(port, 1, null, -1);
	}
	
	public CASSwitch(int port, int networkId, String ccsHost, int ccsPort) {
		this.port = port;
		this.networkId = networkId;
		this.ccsHost = ccsHost;
		this.ccsPort = ccsPort;
	}
	
	@Override
	public void run() {
        if (ccsHost != null) {
            try {
                connectToCCS();
                ccsListenerThread = new Thread(this::listenFromCCS, "CAS-" + networkId + "-CCSListener");
                ccsListenerThread.start();
            } catch (IOException e) {
                log("Could not connect to CCS at " + ccsHost + ":" + ccsPort
                        + " (" + e.getMessage() + ")");
                // We can still run local-only if CCS is down
            }
        }
		
		try(ServerSocket serverSocket = new ServerSocket(port)){
			log("Switch listening on port " + port);
			
			// runs until shutdown is called
			while(running) {
				Socket s;
				
				try {
					s = serverSocket.accept();
				} catch(IOException e) {
					if(!running) {
						break;
					}
					throw e;
				}
				
				s.setTcpNoDelay(true);
				
				ClientHandler handler = new ClientHandler(s);
				synchronized(clients) {
					clients.add(handler);
				}
				
				Thread t = new Thread(handler);
				t.start();
				
				log("Accepted connection from " + s.getRemoteSocketAddress());
			}
		} catch(IOException e) {
			if(running) {
				e.printStackTrace();
			}
		}
		
		log("CAS for network " + networkId + " stopping.");
		
		if(ccsListenerThread != null) {
			try {
				ccsListenerThread.interrupt();
				ccsListenerThread.join(200);
			} catch(InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
	}
	
	// Lets Main tell switch to stop
	public void shutdown() {
		running = false;
		
		if(ccsSocket != null) {
			try {
				ccsSocket.close();
			} catch(IOException ignored) {
				
			}
		}
	}
	
	// Helper to print messages
	private void log(String msg) {
		System.out.println("[CAS " + networkId + "]" + msg);
	}
	
	// Connects to CCS
    private void connectToCCS() throws IOException {
        int attempts = 0;
        while (true) {
            try {
                ccsSocket = new Socket(ccsHost, ccsPort);
                ccsSocket.setTcpNoDelay(true);
                ccsIn = new DataInputStream(new BufferedInputStream(ccsSocket.getInputStream()));
                ccsOut = new DataOutputStream(new BufferedOutputStream(ccsSocket.getOutputStream()));
                log("Connected to CCS " + ccsHost + ":" + ccsPort);
                return;
            } catch (IOException e) {
                attempts++;
                log("CCS not ready (" + e.getMessage() + "), retry " + attempts);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for CCS", ie);
                }
            }
        }
    }

    // Listen for frames coming from CCS and forward them to local nodes
    private void listenFromCCS() {
        if (ccsIn == null) return;

        try {
            while (running) {
                int frameLen;
                try {
                    frameLen = ccsIn.readUnsignedShort();
                } catch (EOFException eof) {
                    break;
                }

                byte[] buf = ccsIn.readNBytes(frameLen);
                Frame frame = Frame.fromBytes(buf);

                frameBuffer.add(frame);
                log("Got frame from CCS " + frame);

                // Frames from CCS should have dstNet == this.networkId
                forwardLocalFrame(frame, null);
            }
        } catch (IOException e) {
            if (running) {
                log("Error reading from CCS: " + e.getMessage());
            }
        } finally {
            try {
                if (ccsSocket != null) ccsSocket.close();
            } catch (IOException ignored) {}
            log("Disconnected from CCS");
        }
    }

    // Send a frame up to CCS
    private void sendToCCS(Frame frame) {
        if (ccsOut == null) {
            log("No CCS connection; dropping frame " + frame);
            return;
        }
        try {
            byte[] bytes = frame.toBytes();
            synchronized (ccsOut) {
                ccsOut.writeShort(bytes.length);
                ccsOut.write(bytes);
                ccsOut.flush();
            }
            log("Sent frame up to CCS " + frame);
        } catch (IOException e) {
            log("Failed to send frame to CCS: " + e.getMessage());
        }
    }
	
	// Forward a frame from the switching table, if destination port is known, send only there, otherwise flood to all except the source port
	private void forwardLocalFrame(Frame frame, ClientHandler from) {
		int dstId = frame.getDst();
		
		ClientHandler dstHandler = table.get(dstId);
		if(dstHandler != null && dstHandler != from) {
			try {
				dstHandler.sendFrame(frame);
			} catch(IOException e) {
				log("Failed to send to node " + dstId);
			}
			return;
		}
		
		// Flood to every port except main port
		synchronized(clients) {
			for(ClientHandler other : clients) {
				if(other == from) {
					continue;
				}
				try {
					other.sendFrame(frame);
				} catch(IOException e) {
					log("Flood failed to local node");
				}
			}
		}
	}
	
    private void handleFrameFromNode(Frame frame, ClientHandler from) {
        int dstId = frame.getDst();
        int dstNet = dstId / 16;

        if (dstNet == this.networkId || ccsHost == null) {
            // Local traffic
            forwardLocalFrame(frame, from);
        } else {
            // Remote network sends to CCS
            sendToCCS(frame);
        }
    }
	
	// Handles a single node connection, reads frames from the node and passes them to forwardFrame
	private class ClientHandler implements Runnable {
		private Socket socket;
		private DataInputStream in;
		private DataOutputStream out;
		
		private Integer nodeId = null;
		
		public ClientHandler(Socket socket) throws IOException {
			this.socket = socket;
			this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
		}
		
		@Override
		public void run() {
			try {
				while(true) {
					int frameLen;
					try {
						frameLen = in.readUnsignedShort();
					} catch(EOFException eof) {
						break;
					}
					
					// Read frame data
					byte[] receivedBytes = in.readNBytes(frameLen);
					Frame frame = Frame.fromBytes(receivedBytes);
					
					int srcId = frame.getSrc();
					learnNodeId(srcId, this);
					frameBuffer.add(frame);
					
					log("Got frame " + frame.toString());
					handleFrameFromNode(frame, this);
				}
			} catch(IOException e) {
				
			} finally {
				cleanup();
			}
		}
		
		// Read the data
		private void learnNodeId(int srcId, ClientHandler handler) {
			table.put(srcId, handler);
			
			if(nodeId == null) {
				nodeId = srcId;
				log("Learned node " + nodeId + " on this connection");
			}
		}
		
		// Send the frame back to node
		public void sendFrame(Frame frame) throws IOException {
			byte[] receivedBytes = frame.toBytes();
			out.writeShort(receivedBytes.length);
			out.write(receivedBytes);
			out.flush();
		}
		
		// Close the socket
		private void cleanup() {
			try {
				socket.close();
			} catch(IOException ignored) {
				
			}
			
			if(nodeId != null) {
				ClientHandler current = table.get(nodeId);
				if(current == this) {
					table.remove(nodeId);
					log("Node " + nodeId + " disconnected");
				}
			}
			
			synchronized(clients) {
				clients.remove(this);
			}
		}
	}
}
