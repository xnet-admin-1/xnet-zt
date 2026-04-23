package ngo.xnet.zerotier.events;

public class LeaveNetworkEvent {
    long networkId;

    public LeaveNetworkEvent(long j) {
        this.networkId = j;
    }

    public long getNetworkId() {
        return this.networkId;
    }
}
