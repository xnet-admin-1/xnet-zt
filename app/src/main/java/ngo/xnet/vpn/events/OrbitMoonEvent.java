package ngo.xnet.vpn.events;

import ngo.xnet.vpn.model.MoonOrbit;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Moon 入轨事件
 * <p>
 * 事件发生时应实际处理入轨动作
 *
 * @author xnet
 */
@Data
@AllArgsConstructor
public class OrbitMoonEvent {

    /**
     * 待入轨 Moon 的信息
     */
    private List<MoonOrbit> moonOrbits;
}
