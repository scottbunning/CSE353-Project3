package p1;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Main {

	public static void main(String[] args) {
		if(args.length < 3) {
			System.out.println("Usage: java p1.Main <nodesPerNet> <casPort> <ccsPort> [host=127.0.0.1]");
			System.out.println("Example: java p1.Main 3 5000 6000");
			return;
		}
		
		int nodesPerNet, casPort, ccsPort;
		int shadowCcsPort;
		try {
			nodesPerNet = Integer.parseInt(args[0]);
			casPort = Integer.parseInt(args[1]);
			ccsPort = Integer.parseInt(args[2]);
			shadowCcsPort = ccsPort + 1;
		} catch(NumberFormatException e) {
			System.err.println("Bad args: " + e.getMessage());
			return;
		}
		
		if(nodesPerNet < 1 || nodesPerNet > 15) {
			System.err.println("nodesPerNet must be between 1 and 15.");
			return;
		}
		
		String host = "localhost";
		int numNetworks = 3;
		
		// Start the switch thread so it can accept connections
		CCSSwitch ccs = new CCSSwitch(ccsPort);
		CCSShadowSwitch shadowCcs= new CCSShadowSwitch(shadowCcsPort);
		Thread ccsThread = new Thread(ccs, "CCS");
		Thread shadowCCSThread = new Thread(shadowCcs, "CCS-Shadow");
		ccsThread.start();
		shadowCCSThread.start();
		
		CASSwitch[] casArray = new CASSwitch[numNetworks + 1];
		Thread[] casThreads = new Thread[numNetworks + 1];
		
		for(int net = 1; net <= numNetworks; net++) {
			int casBasePort = casPort + net;
			CASSwitch cas = new CASSwitch(casBasePort, net, host, ccsPort);
			casArray[net] = cas;
			Thread t = new Thread(cas, "CAS-" + net);
			casThreads[net] = t;
			t.start();
		}
		
		try {
			Thread.sleep(150);
		} catch(InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		
        List<Thread> nodeThreads = new ArrayList<>();

        for (int net = 1; net <= numNetworks; net++) {
            int casBasePort = casPort + net;
            for (int localId = 1; localId <= nodesPerNet; localId++) {
                Node node = new Node(net, localId, host, casBasePort);
                Thread t = new Thread(node,
                        "Node" + net + "_" + localId);
                t.start();
                nodeThreads.add(t);
            }
        }

        // Wait for all nodes to finish
        for (Thread t : nodeThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for " + t.getName());
            }
        }

        // Shut down CAS's
        for (int net = 1; net <= numNetworks; net++) {
            casArray[net].shutdown();
            int casBasePort = casPort + net;
            // dummy connect to break accept()
            try {
                new Socket(host, casBasePort).close();
            } catch (Exception ignored) {}
        }

        // Shut down CSS
        ccs.shutdown();
        shadowCcs.shutdown();
        try {
            new Socket(host, ccsPort).close();
        } catch (Exception ignored) {}

        try {
        	new Socket(host, shadowCcsPort).close();
        } catch(Exception ignored) {}
        
        try {
            for (int net = 1; net <= numNetworks; net++) {
                casThreads[net].join();
            }
            ccsThread.join();
            shadowCCSThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for switches to stop.");
        }

        System.out.println("[Main] All nodes and switches finished.");
    }

}
