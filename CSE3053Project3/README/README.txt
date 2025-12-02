Scott Bunning
12/1/2025
CSE 3053-01

README

How to Compile and Run
- To compile and run the program, you must use “make clean” to clean object files, then “make” to compile, then “make run ARGS=“<nodesPerNet> <casPort> <ccsPort>” ” which then runs the files with the number of nodes and the port number.

File Descriptions
- Main: Starts the CAS and CCS switches, nodes, then waits for them to complete, then shuts the switches down.
- CASSwitch: Local switch that learns the node IDS, floods when unknown, forwards locally or sends traffic to CCS, and receives ACK from firewall.
- CCSSwitch: Central switch that helps traffic from CAS switches, uses global firewall rules, and forwards traffic based on the network ID.
- CCSShadowSwitch: Exact same as CCSSwitch, but runs on  a different port and can take over forwarding if needed.
- Node: Represents a network node. Connects to the switch and sends data read from the input file, receives incoming frames, and logs them to the node#output.txt file and sends acknowledgement for the received messages.
- Frame: Uses the frame format that is used for communication between nodes and the switch.
- node#_#.txt: Holds data that is read.
- firewall.txt: Holds firewall rules for CCS switch.
- Makefile: Compiles and runs the code.
- README: Describes the project.

Frame Format
Byte Offset		Name		Size		Range		Description

0				SrcId		1 byte		0-255		Network/local ID
1				DstId		1 byte		0-255		Target node ID
2				CRC			1 byte		0-255		Checks the frame
3				Size/ACK	1 byte		0-255		Payload length or 0 for ACK
4				ACK type	1 byte		below		Valid if Size = 0
5				Data		0-255 bytes				Message payload

ACK Types
0x00 - Timeout
0x01 - CRC error
0x10 - Firewall blocked
0x11 - Success

Checklist

Feature													Status/Description
Project compiles and builds with no warnings or errors.
															Complete
Switch Class
															Complete
CAS, CCS Switches has a frame buffer, and read/writes appropriately
															Complete
CAS, CCS Switches allows multiple connections
															Complete
CAS, CCS Switches floods frame when it doesn’t know the destination
															Complete
CAS, CCS Switches learns destinations, and doesn’t forward packet to any port except the one required
															Complete
CAS connects to CCS
															Complete
CAS receives local firewall rules
															Complete
CAS forwards traffic and ACKs properly
															Complete
CCS switch opens the firewall file and gets the rule
															Complete
CCS passes global traffic
															Complete
CCS does the global firewalls
															Complete
CCS Shadow switches run and test properly
															Complete
Node Class
															Complete
Node instantiate, and open connection to the switch
															Complete
Nodes open their input files, and send data to switch
															Complete
Nodes open their output files, and save data that they receive
															Complete
Node will sometimes drop acknowledgement
															Complete
Node will sometimes create erroneous frame					
															Complete
Node will sometimes reject traffic
															Complete


List of Known Bugs
- For shutdown timing, there is a little delay to receive data and send acknowledgement before disconnecting.
- Rules for firewall must be written both ways if you want nothing sent for both or vice versa.
- Makes output.txt files for nodes that are skipped over or aren't receiving a message.



Resources
https://docs.oracle.com/javase/8/docs/api/java/nio/charset/StandardCharsets.html
https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html
https://docs.oracle.com/javase/tutorial/networking/sockets/index.html
https://docs.oracle.com/javase/7/docs/api/java/lang/Thread.html
https://www.geeksforgeeks.org/java/java-nio-bytebuffer-class-in-java/

