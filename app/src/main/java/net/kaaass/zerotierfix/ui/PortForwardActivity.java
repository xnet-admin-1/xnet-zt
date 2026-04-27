package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.DeviceDiscovery;
import net.kaaass.zerotierfix.util.PortForwarder;
import net.kaaass.zerotierfix.service.ZeroTierOneService;
import java.util.*;

public class PortForwardActivity extends AppCompatActivity {
    private LinearLayout deviceList;
    private TextView statusText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> portSpecs = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_port_forward);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("Port Forward");
        deviceList = findViewById(R.id.device_list);
        statusText = findViewById(R.id.status_text);
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        refresh();
    }

    private void refresh() {
        statusText.setText("Scanning...");
        deviceList.removeAllViews();
        new Thread(() -> {
            List<DeviceDiscovery.Device> devices = DeviceDiscovery.findTetheredDevices();
            handler.post(() -> {
                deviceList.removeAllViews();
                if (devices.isEmpty()) {
                    statusText.setText("No tethered devices found");
                    return;
                }
                statusText.setText(devices.size() + " device(s) found");
                for (DeviceDiscovery.Device dev : devices) {
                    addDeviceRow(dev);
                }
            });
        }).start();
    }

    private void addDeviceRow(DeviceDiscovery.Device dev) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_port_forward, deviceList, false);
        TextView ipText = row.findViewById(R.id.device_ip);
        TextView macText = row.findViewById(R.id.device_mac);
        EditText portsInput = row.findViewById(R.id.ports_input);
        Switch toggle = row.findViewById(R.id.forward_toggle);

        ipText.setText(dev.ip);
        macText.setText(dev.mac);
        String saved = portSpecs.get(dev.ip);
        if (saved != null) portsInput.setText(saved);
        toggle.setChecked(PortForwarder.isActive(dev.ip));

        toggle.setOnCheckedChangeListener((btn, on) -> {
            String spec = portsInput.getText().toString().trim();
            portSpecs.put(dev.ip, spec);
            if (on) {
                if (spec.isEmpty()) {
                    Toast.makeText(this, "Enter port(s) first", Toast.LENGTH_SHORT).show();
                    toggle.setChecked(false);
                    return;
                }
                try {
                    PortForwarder.startForDevice(dev.ip, spec,
                        ZeroTierOneService.getInstance());
                    Toast.makeText(this, "Forwarding → " + dev.ip, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    toggle.setChecked(false);
                }
            } else {
                PortForwarder.stopForDevice(dev.ip);
                Toast.makeText(this, "Stopped → " + dev.ip, Toast.LENGTH_SHORT).show();
            }
        });

        deviceList.addView(row);
    }
}
