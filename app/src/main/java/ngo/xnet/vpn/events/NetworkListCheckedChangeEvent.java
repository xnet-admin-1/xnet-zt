package ngo.xnet.vpn.events;

import androidx.appcompat.widget.SwitchCompat;

import ngo.xnet.vpn.model.Network;

import lombok.Data;

/**
 * 网络列表点击按钮事件。用于在后台进程控制 ZT 服务的启停
 */
@Data
public class NetworkListCheckedChangeEvent {
    private final SwitchCompat switchHandle;
    private final boolean checked;
    private final Network selectedNetwork;
}
