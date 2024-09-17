# Peer2Peer-FileShare
## Contents
- `data`
  - `d{i}`: Data for peer `i`
  - `populate.py`: Utility script to add a file and keyword to a directory
- `src`
  - `Main`:
  Entrypoint to program
  - `Constants`:
  Contains port number configurations
  - `FileDirectory`:
  Processes contents of data directory to generate list of available files and keywords
  - `tasks`: Background tasks running on each node
    - `DiscoveryServer`: Server for joining and accepting connections from resource discovery network
    - `PeerDiscoveryTransceiver`: For future use
    - `TrackerServer`: Server for tracking all nodes in network
    - `ListenerTask`: Base class for servers
  - `messages`: Message formats
    - `DiscoveryMessage`: Base class containing common data to all discovery messages
    - `DiscoveryQueryMessage`: For future use
    - `DiscoveryReplyMessage`: For future use
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
java -cp ./out/ pfs.Main
```

#### Peer
For each integer `i` from 1 to 15, pick a unique host and start peer `i` with:
```
java -cp ./out/ pfs.Main ./data/d{i} {tracker hostname}
```

*curly braces {} indicates substitution with the appropriate variable

*the tracker server can run on the same host as a peer, for example peer 1

## Output
Each peer will log the following items:
- CONNECT TO <hostname>: Peer is sending a connection request to specified hostname
- CONNECT FROM <hostname>: Peer is receiving a connection request from specified hostname
- NEIGHBORS <hostnames>: Peer currently connected to the following hostnames (logged on connection request sent/received)