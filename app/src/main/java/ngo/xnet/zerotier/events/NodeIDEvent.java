package ngo.xnet.zerotier.events;

public class NodeIDEvent {
    private final long nodeId;

    public NodeIDEvent(long j) {
        this.nodeId = j;
    }

    public long getNodeId() {
        return this.nodeId;
    }
}
