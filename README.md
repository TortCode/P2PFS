# Peer2Peer-FileShare
## Contents
- `initdata`
  - `d{i}`: Initial data for peer `i`
  - `populate.py`: Utility script to add a file and keyword to a directory
- `src`
  - `Main`:
  Entrypoint to program
  - `Constants`:
  Contains port number configurations
  - `FileDirectory`:
  Processes contents of data directory to generate list of available files and keywords
  - `tasks`: Background tasks running on each node
    - `Node`: Interface to entire peer network, including join/leave, discovery, and file transfers
    - `PeerDiscoveryTransceiver`: Task for transmitting and receiving data from a particular neighbor
    - `TrackerServer`: Server for tracking all nodes in network
    - `ListenerTask`: Base class for servers
  - `messages`: Message formats
    - `Message`: Base class for messages, with utility methods for serializing byte arrays
    - `HangupMessage`: Represents hangup messages (for disconnecting from network)
    - `DiscoveryMessage`: Base class containing common data to all discovery messages
    - `DiscoveryQueryMessage`: Represents discovery query messages
    - `DiscoveryReplyMessage`: Represents discovery reply messages
## Instructions
### Compilation
Compile the Java source files into class files:
```
find ./src/ -type f -name "*.java" > sources.txt
javac -d ./out/ @sources.txt
```

### Execution
#### Tracker
Start a tracker server on any host (note the hostname down) with:
```
./tracker.sh
```

#### Peer
For each integer `i` from 1 to 15, pick a unique host and start peer `i` with:
```
./node.sh {trackerhostname} {i}
```

*curly braces {} indicates substitution with the appropriate variable

*the tracker server can run on the same host as a peer, for example peer 1

## Output
Each peer will log the following items:
- CONNECT TO <hostname>: Peer is sending a connection request to specified hostname
- CONNECT FROM <hostname>: Peer is receiving a connection request from specified hostname
- NEIGHBORS <hostnames>: Peer currently connected to the following hostnames (logged on connection request sent/received)
- SEND <details...>: Sending a message
- RECV <details...>: Receiving a message