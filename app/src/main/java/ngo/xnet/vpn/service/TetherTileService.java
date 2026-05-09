package ngo.xnet.vpn.service;

import android.content.ComponentName;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import ngo.xnet.vpn.tether.TetherBridge;
import ngo.xnet.vpn.tether.TetherConfig;

/**
 * Quick Settings tile for toggling tether bridge on/off.
 * Shows active state and connection count.
 */
public class TetherTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        TetherConfig config = new TetherConfig(this);
        config.setEnabled(!config.isEnabled());
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        TetherConfig config = new TetherConfig(this);
        ZeroTierOneService svc = ZeroTierOneService.getInstance();
        TetherBridge bridge = svc != null ? svc.getTetherBridge() : null;

        if (!config.isEnabled()) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("XNet Tether");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Off");
            }
        } else if (bridge != null && bridge.getState() == TetherBridge.State.ACTIVE) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("XNet Tether");
            int conns = bridge.getDetector().getActiveInterfaces().size();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(conns + " interface(s)");
            }
        } else {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("XNet Tether");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("Waiting");
            }
        }
        tile.updateTile();
    }
}
