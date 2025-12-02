package p1;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CCSSwitch implements Runnable {
	private final int port;
	private boolean running = true;
	private static final int MAX_NET = 16;
	private final boolean[][] firewallBlock = new boolean[MAX_NET][MAX_NET];
	
	private final Map<Integer, ClientHandler> networkTable = new HashMap<>();
	
	private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
	
	private final List<Frame> frameBuffer = new ArrayList<>();
	
	public CCSSwitch(int port) {
		this.port = port;
		loadFirewallRules("firewall.txt");
	}
	
	@Override
	public void run() {
		try(ServerSocket serverSocket = new ServerSocket(port)) {
			log("CCS listening on port " + port);
			
			while(running) {
				Socket s;
				try {
					s= serverSocket.accept();
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
				
				log("Accepted CAS connection from " + s.getRemoteSocketAddress());
			}
		} catch(IOException e) {
			if(running) {
				e.printStackTrace();
			}
		}
		log("CCS stopping.");
	}
	
	// Loads up the rules from firewall.txt
	private void loadFirewallRules(String filename) {
		for(int i = 0; i < MAX_NET; i++) {
			Arrays.fill(firewallBlock[i], false);
		}
		
		try(BufferedReader br = new BufferedReader(new FileReader(filename))) {
			String line;
			int count = 0;
			while((line = br.readLine()) != null) {
				line = line.trim();
				if(line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\s+");
				if(parts.length < 3) {
					log("Skipping bad firewall line: " + line);
					continue;
				}
				
				int srcNet;
				int dstNet;
				try {
					srcNet = Integer.parseInt(parts[0]);
					dstNet = Integer.parseInt(parts[1]);
				} catch(NumberFormatException e) {
					log("Skipping firewall line with bad nets: " + line);
					continue;
				}
				
				String action = parts[2].toLowerCase();
				boolean isBlock = action.startsWith("block") || action.startsWith("deny") || action.equals("0");
				
				if(srcNet >= 0 && srcNet < MAX_NET && dstNet >= 0 && dstNet < MAX_NET) {
					firewallBlock[srcNet][dstNet] = isBlock;
					count++;
				} else {
					log("Skipping firewall line with out of range nets: " + line);
				}
			}
			log("Loaded " + count + " firewall rules from " + filename);
		} catch(IOException e) {
			log("Could not load firewall rules from " + filename + ": " + e.getMessage());
			log("Defaulting to no firewall.");
		}
	}
	
	// Checks if blocked
	private boolean isBlocked(int srcNet, int dstNet) {
		if(srcNet < 0 || srcNet >= MAX_NET || dstNet < 0 || dstNet >= MAX_NET) {
			return false;
		}
		return firewallBlock[srcNet][dstNet];
	}
	
	// Shuts down
	public void shutdown() {
		running = false;
	}
	
	// Helper log
	private void log(String msg) {
		System.out.println("[CCS] " + msg);
	}
	
	// Forwards the frame
	private void forwardFrame(Frame frame, ClientHandler from) {
		int dstId = frame.getDst();
		int dstNet = dstId / 16;
		
		ClientHandler dstHandler = networkTable.get(dstNet);
		if(dstHandler != null && dstHandler != from) {
			try {
				dstHandler.sendFrame(frame);
			} catch(IOException e) {
				log("Failed to send to network " + dstNet);
			}
			return;
		}
		
		synchronized(clients) {
			for(ClientHandler other : clients) {
				if(other == from) continue;
				try {
					other.sendFrame(frame);
				} catch(IOException e) {
					log("Flood failed to a connected CAS");
				}
			}
		}
	}
	
	// Handles the frame from CAS
	private void handleFrameFromCAS(Frame frame, ClientHandler from) {
		int srcId = frame.getSrc();
		int dstId = frame.getDst();
		int srcNet = srcId / 16;
		int dstNet = dstId / 16;
		
		log("Got frame from CAS net=" + from.networkId + " " + frame.toString());
		
		if(frame.isAck()) {
			forwardFrame(frame, from);
			return;
		}
		
		if(isBlocked(srcNet, dstNet)) {
			log("Firewall BLOCKED traffic " + srcNet + " to " + dstNet + " (srcId=" + srcId + ", dst=" + dstId + ")");
			
			int ackSrcId = dstId;
			int ackDstId = srcId;
			
			Frame nack = Frame.ackFrame(ackSrcId, ackDstId, Frame.ACK_FIREWALL);
			
			forwardFrame(nack, null);
			return;
		}
		forwardFrame(frame, from);
	}
	
	private class ClientHandler implements Runnable {
		private final Socket socket;
		private final DataInputStream in;
		private final DataOutputStream out;
		
		private Integer networkId = null;
		
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
					
					byte[] recievedBytes = in.readNBytes(frameLen);
					Frame frame = Frame.fromBytes(recievedBytes);
					
					int srcId = frame.getSrc();
					int srcNet = srcId / 16;
					
					learnNetwork(srcNet, this);
					frameBuffer.add(frame);
					
					handleFrameFromCAS(frame, this);
				}
			} catch(IOException e) {
				
			} finally {
				cleanup();
			}
		}
		
		// Learns the network from networkID
		private void learnNetwork(int netId, ClientHandler handler) {
			networkTable.put(netId, handler);
			
			if(networkId == null) {
				networkId = netId;
				log("Learned CAS for network " + networkId + " on this connection.");
			}
		}
		
		// Sends the frame
		public void sendFrame(Frame frame) throws IOException {
			byte[] bytes = frame.toBytes();
			out.writeShort(bytes.length);
			out.write(bytes);
			out.flush();
		}
		
		// Closes the sockets
		private void cleanup() {
			try {
				socket.close();
			} catch(IOException ignored) {
				
			}
			
			if(networkId != null) {
				ClientHandler current = networkTable.get(networkId);
				if(current == this) {
					networkTable.remove(networkId);
					log("CAS for network " + networkId + " disconnected");
				}
			}
			
			synchronized(clients) {
				clients.remove(this);
			}
		}
	}
}
