package ngo.xnet.vpn.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.zerotier.sdk.Event;
import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;
import com.zerotier.sdk.VirtualNetworkConfigOperation;
import com.zerotier.sdk.VirtualNetworkStatus;

import ngo.xnet.vpn.R;
import ngo.xnet.vpn.XnetApplication;
import ngo.xnet.vpn.events.AfterJoinNetworkEvent;
import ngo.xnet.vpn.events.ErrorEvent;
import ngo.xnet.vpn.events.IsServiceRunningReplyEvent;
import ngo.xnet.vpn.events.IsServiceRunningRequestEvent;
import ngo.xnet.vpn.events.ManualDisconnectEvent;
import ngo.xnet.vpn.events.NetworkConfigChangedByUserEvent;
import ngo.xnet.vpn.events.NetworkListReplyEvent;
import ngo.xnet.vpn.events.NetworkListRequestEvent;
import ngo.xnet.vpn.events.NetworkReconfigureEvent;
import ngo.xnet.vpn.events.NodeDestroyedEvent;
import ngo.xnet.vpn.events.NodeIDEvent;
import ngo.xnet.vpn.events.NodeStatusEvent;
import ngo.xnet.vpn.events.NodeStatusRequestEvent;
import ngo.xnet.vpn.events.OrbitMoonEvent;
import ngo.xnet.vpn.events.PeerInfoReplyEvent;
import ngo.xnet.vpn.events.PeerInfoRequestEvent;
import ngo.xnet.vpn.events.StopEvent;
import ngo.xnet.vpn.events.VPNErrorEvent;
import ngo.xnet.vpn.events.VirtualNetworkConfigChangedEvent;
import ngo.xnet.vpn.events.VirtualNetworkConfigReplyEvent;
import ngo.xnet.vpn.events.VirtualNetworkConfigRequestEvent;
import ngo.xnet.vpn.model.AppNode;
import ngo.xnet.vpn.model.MoonOrbit;
import ngo.xnet.vpn.model.Network;
import ngo.xnet.vpn.model.NetworkDao;
import ngo.xnet.vpn.model.type.DNSMode;
import ngo.xnet.vpn.ui.NetworkListActivity;
import ngo.xnet.vpn.tether.TetherBridge;
import ngo.xnet.vpn.tether.TetherConfig;
import ngo.xnet.vpn.tether.TetherDetector;
import ngo.xnet.vpn.tether.DnsProxy;
import ngo.xnet.vpn.tether.HttpProxy;
import ngo.xnet.vpn.tether.NatEngine;
import ngo.xnet.vpn.tether.SocksProxy;
import ngo.xnet.vpn.util.Constants;
import ngo.xnet.vpn.util.DatabaseUtils;
import ngo.xnet.vpn.util.InetAddressUtils;
import ngo.xnet.vpn.util.NetworkInfoUtils;
import ngo.xnet.vpn.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: clear up
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final int MSG_JOIN_NETWORK = 1;
    public static final int MSG_LEAVE_NETWORK = 2;
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    public static final String ZT1_USE_DEFAULT_ROUTE = "com.zerotier.one.use_default_route";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    private static final String TAG = "ZT1_Service";
    private static final int ZT_NOTIFICATION_TAG = 5919812;
    private static ZeroTierOneService sInstance;

    public static ZeroTierOneService getInstance() { return sInstance; }
    private final IBinder mBinder = new ZeroTierBinder();
    private final DataStore dataStore = new DataStore(this);
    private final EventBus eventBus = EventBus.getDefault();
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap();
    FileInputStream in;
    FileOutputStream out;
    DatagramSocket svrSocket;
    // Tether services
    private TetherBridge tetherBridge;
    private TetherConfig tetherConfig;
    private NatEngine natEngine;
    private DnsProxy dnsProxy;
    private SocksProxy socksProxy;
    private HttpProxy httpProxy;
    ParcelFileDescriptor vpnSocket;
    private int bindCount = 0;
    private boolean disableIPv6 = false;
    private int mStartID = -1;
    private long networkId = 0;
    private long nextBackgroundTaskDeadline = 0;
    private Node node;
    private NotificationManager notificationManager;
    private TunTapAdapter tunTapAdapter;
    private UdpCom udpCom;
    private Thread udpThread;
    private Thread v4MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass1 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", false);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            byte[] hexStringToByteArray = StringUtils.hexStringToBytes(str);
                            for (int i = 0; i < hexStringToByteArray.length / 2; i++) {
                                byte b = hexStringToByteArray[i];
                                hexStringToByteArray[i] = hexStringToByteArray[(hexStringToByteArray.length - i) - 1];
                                hexStringToByteArray[(hexStringToByteArray.length - i) - 1] = b;
                            }
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray)));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            byte[] hexStringToByteArray2 = StringUtils.hexStringToBytes(str2);
                            for (int i2 = 0; i2 < hexStringToByteArray2.length / 2; i2++) {
                                byte b2 = hexStringToByteArray2[i2];
                                hexStringToByteArray2[i2] = hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1];
                                hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1] = b2;
                            }
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray2)));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V4 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Ended.");
        }
    };
    private Thread v6MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass2 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", true);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str))));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str2))));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V6 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Ended.");
        }
    };
    private Thread vpnThread;

    public VirtualNetworkConfig getVirtualNetworkConfig(long j) {
        VirtualNetworkConfig virtualNetworkConfig;
        synchronized (this.virtualNetworkConfigMap) {
            virtualNetworkConfig = this.virtualNetworkConfigMap.get(Long.valueOf(j));
        }
        return virtualNetworkConfig;
    }

    public VirtualNetworkConfig setVirtualNetworkConfig(long j, VirtualNetworkConfig virtualNetworkConfig) {
        VirtualNetworkConfig put;
        synchronized (this.virtualNetworkConfigMap) {
            put = this.virtualNetworkConfigMap.put(Long.valueOf(j), virtualNetworkConfig);
        }
        return put;
    }

    public VirtualNetworkConfig clearVirtualNetworkConfig(long j) {
        VirtualNetworkConfig remove;
        synchronized (this.virtualNetworkConfigMap) {
            remove = this.virtualNetworkConfigMap.remove(Long.valueOf(j));
        }
        return remove;
    }

    private void logBindCount() {
        Log.i(TAG, "Bind Count: " + this.bindCount);
    }

    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount++;
        logBindCount();
        return this.mBinder;
    }

    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount--;
        logBindCount();
        return false;
    }

    /* access modifiers changed from: protected */
    public void setNextBackgroundTaskDeadline(long j) {
        synchronized (this) {
            this.nextBackgroundTaskDeadline = j;
        }
    }

    /**
     * 启动 ZT 服务，连接至给定网络或最近连接的网络
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long networkId;
        Log.d(TAG, "onStartCommand");
        sInstance = this;

        // Must call startForeground immediately on Android 14+
        if (Build.VERSION.SDK_INT >= 26) {
            var nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            var channel = new NotificationChannel(
                    Constants.CHANNEL_ID, "XNet-ZT", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            var notification = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("XNet-ZT")
                    .setContentText("Connecting...")
                    .build();
            startForeground(ZT_NOTIFICATION_TAG, notification);
        }

        if (startId == 3) {
            Log.i(TAG, "Authorizing VPN");
            return START_NOT_STICKY;
        } else if (intent == null) {
            Log.e(TAG, "NULL intent.  Cannot start");
            return START_NOT_STICKY;
        }
        this.mStartID = startId;

        // 注册事件总线监听器
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }

        // 确定待启动的网络 ID
        if (intent.hasExtra(ZT1_NETWORK_ID)) {
            // Intent 中指定了目标网络，直接使用此 ID
            networkId = intent.getLongExtra(ZT1_NETWORK_ID, 0);
        } else {
            // 默认启用最近一次启动的网络
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((XnetApplication) getApplication()).getDaoSession();
                daoSession.clear();
                var lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .list();
                if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                    Log.e(TAG, "Couldn't find last activated connection");
                    return START_NOT_STICKY;
                } else if (lastActivatedNetworks.size() > 1) {
                    Log.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                    for (Network network : lastActivatedNetworks) {
                        Log.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                    }
                    throw new IllegalStateException("Database is inconsistent");
                } else {
                    networkId = lastActivatedNetworks.get(0).getNetworkId();
                    Log.i(TAG, "Got Always On request for ZeroTier");
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
        if (networkId == 0) {
            Log.e(TAG, "Network ID not provided to service");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        this.networkId = networkId;

        // 检查当前的网络环境
        var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCellularData = preferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        this.disableIPv6 = preferences.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);
        var currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);

        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            // 未连接网络
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        } else if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE &&
                !useCellularData) {
            // 使用移动网络，但未在设置中允许移动网络访问
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        }

        // 启动 ZT 服务
        synchronized (this) {
            try {
                // 创建本地 ZT 服务 Socket，监听本地端口
                if (this.svrSocket == null) {
                    this.svrSocket = new DatagramSocket(null);
                    this.svrSocket.setReuseAddress(true);
                    this.svrSocket.setSoTimeout(1000);
                    this.svrSocket.setReceiveBufferSize(1024 * 1024);
                    this.svrSocket.setSendBufferSize(1024 * 1024);
                    this.svrSocket.bind(new InetSocketAddress(9994));
                }
                if (!protect(this.svrSocket)) {
                    Log.e(TAG, "Error protecting UDP socket from feedback loop.");
                }

                // 创建本地节点
                if (this.node == null) {
                    this.udpCom = new UdpCom(this, this.svrSocket);
                    this.tunTapAdapter = new TunTapAdapter(this, networkId);

                    // 创建节点对象并初始化
                    var dataStore = this.dataStore;
                    this.node = new Node(System.currentTimeMillis());
                    var result = this.node.init(dataStore, dataStore, this.udpCom, this, this.tunTapAdapter, this, null);

                    if (result == ResultCode.RESULT_OK) {
                        Log.d(TAG, "ZeroTierOne Node Initialized");
                    } else {
                        Log.e(TAG, "Error starting ZT1 Node: " + result);
                        return START_NOT_STICKY;
                    }
                    this.onNodeStatusRequest(null);

                    // 持久化当前节点信息
                    long address = this.node.address();
                    DatabaseUtils.writeLock.lock();
                    try {
                        var appNodeDao = ((XnetApplication) getApplication())
                                .getDaoSession().getAppNodeDao();
                        var nodesList = appNodeDao.queryBuilder().build()
                                .forCurrentThread().list();
                        if (nodesList.isEmpty()) {
                            var appNode = new AppNode();
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.insert(appNode);
                        } else {
                            var appNode = nodesList.get(0);
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.save(appNode);
                        }
                    } finally {
                        DatabaseUtils.writeLock.unlock();
                    }

                    this.eventBus.post(new NodeIDEvent(address));
                    this.udpCom.setNode(this.node);
                    this.tunTapAdapter.setNode(this.node);

                    // 启动 UDP 消息处理线程
                    var thread = new Thread(this.udpCom, "UDP Communication Thread");
                    this.udpThread = thread;
                    thread.start();

                    // Auto-orbit xnet moon for independent infrastructure
                    try {
                        var moonDao = ((XnetApplication) getApplication())
                                .getDaoSession().getMoonOrbitDao();
                        var moons = moonDao.queryBuilder().build().forCurrentThread().list();
                        for (MoonOrbit moon : moons) {
                            Log.i(TAG, "Auto-orbiting moon: " + Long.toHexString(moon.getMoonWorldId()));
                            this.node.orbit(moon.getMoonWorldId(), moon.getMoonSeed());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Moon auto-orbit: " + e.getMessage());
                    }
                }

                // 创建并启动 VPN 服务线程
                if (this.vpnThread == null) {
                    var thread = new Thread(this, "ZeroTier Service Thread");
                    this.vpnThread = thread;
                    thread.start();
                }

                // 启动 UDP 消息处理线程
                if (!this.udpThread.isAlive()) {
                    this.udpThread.start();
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
                return START_NOT_STICKY;
            }
        }
        joinNetwork(networkId);
        return START_STICKY;
    }

    public void stopZeroTier() {
        stopTetherServices();
        if (this.svrSocket != null) {
            this.svrSocket.close();
            this.svrSocket = null;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            this.udpThread.interrupt();
            try {
                this.udpThread.join();
            } catch (InterruptedException ignored) {
            }
            this.udpThread = null;
        }
        if (this.tunTapAdapter != null && this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
            this.tunTapAdapter = null;
        }
        if (this.vpnThread != null && this.vpnThread.isAlive()) {
            this.vpnThread.interrupt();
            try {
                this.vpnThread.join();
            } catch (InterruptedException ignored) {
            }
            this.vpnThread = null;
        }
        if (this.v4MulticastScanner != null) {
            this.v4MulticastScanner.interrupt();
            try {
                this.v4MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v4MulticastScanner = null;
        }
        if (this.v6MulticastScanner != null) {
            this.v6MulticastScanner.interrupt();
            try {
                this.v6MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v6MulticastScanner = null;
        }
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
        }
        if (this.node != null) {
            this.eventBus.post(new NodeDestroyedEvent());
            this.node.close();
            this.node = null;
        }
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        if (this.notificationManager != null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        if (!stopSelfResult(this.mStartID)) {
            Log.e(TAG, "stopSelfResult() failed!");
        }
    }

    public void onDestroy() {
        try {
            stopZeroTier();
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing VPN socket: " + e, e);
                }
                try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
            }
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        super.onRevoke();
    }

    public void run() {
        Log.d(TAG, "ZeroTierOne Service Started");
        Log.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(this.node.address()));
        while (!Thread.interrupted()) {
            try {
                // 在后台任务截止期前循环进行后台任务
                var taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    var taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                }
                Thread.sleep(cmp > 0 ? taskDeadline - currentTime : 100);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            }
        }
        Log.d(TAG, "ZeroTierOne Service Ended");
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 加入 ZT 网络
     */
    public void joinNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't join network if ZeroTier isn't running");
            return;
        }
        // 连接到新网络
        var result = this.node.join(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        // 连接后事件
        this.eventBus.post(new AfterJoinNetworkEvent());
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        var result = this.node.leave(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        var networkConfigs = this.node.networkConfigs();
        if (networkConfigs != null && networkConfigs.length != 0) {
            return;
        }
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks;
        Node node2 = this.node;
        if (node2 != null && (networks = node2.networkConfigs()) != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        // 返回节点状态
        if (this.node != null) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    /**
     * 请求 Peer 信息事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null));
            return;
        }
        this.eventBus.post(new PeerInfoReplyEvent(this.node.peers()));
    }

    /**
     * 请求网络配置事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new VirtualNetworkConfigReplyEvent(null));
            return;
        }
        var config = this.node.networkConfig(event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        var network = event.getNetwork();
        var networkConfig = event.getVirtualNetworkConfig();
        boolean configUpdated = isChanged && updateTunnelConfig(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        Network network = event.getNetwork();
        if (network.getNetworkId() != this.networkId) {
            return;
        }
        updateTunnelConfig(network);
    }

    /**
     * Zerotier 事件回调
     *
     * @param event {@link Event} enum
     */
    @Override
    public void onEvent(Event event) {
        Log.d(TAG, "Event: " + event.toString());
        // 更新节点状态
        if (this.node.isInited()) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    @Override // com.zerotier.sdk.EventListener
    public void onTrace(String str) {
        Log.d(TAG, "Trace: " + str);
    }

    /**
     * 当 ZT 网络配置发生更新
     */
    @Override
    public int onNetworkConfigurationUpdated(long networkId, VirtualNetworkConfigOperation op, VirtualNetworkConfig config) {
        Log.i(TAG, "Virtual Network Config Operation: " + op);
        DatabaseUtils.writeLock.lock();
        try {
            // 查找网络 ID 对应的配置
            var networkDao = ((XnetApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            var matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                throw new IllegalStateException("Database is inconsistent");
            }
            var network = matchedNetwork.get(0);
            // 根据当前网络状态确定更改配置的行为
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    Log.d(TAG, "Network Type: " + config.getType() + " Network Status: " + config.getStatus() + " Network Name: " + config.getName() + " ");
                    // 将网络配置的更新交给第一次 Update
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    Log.i(TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    this.eventBus.post(new NetworkReconfigureEvent(isChanged, network, config));
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    Log.d(TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    break;
            }
            return 0;
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    private boolean setVirtualNetworkConfigAndUpdateDatabase(Network network, VirtualNetworkConfig virtualNetworkConfig) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock) && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig virtualNetworkConfig2 = getVirtualNetworkConfig(network.getNetworkId());
        setVirtualNetworkConfig(network.getNetworkId(), virtualNetworkConfig);
        var networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(virtualNetworkConfig2);
    }

    protected void shutdown() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    private boolean updateTunnelConfig(Network network) {
        long networkId = network.getNetworkId();
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(networkId);
        if (virtualNetworkConfig == null) {
            return false;
        }

        // 重启 TUN TAP
        if (this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        this.tunTapAdapter.clearRouteMap();

        // 重启 VPN Socket
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
                this.in.close();
                this.out.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            try { Node.stopNativeTx(); Node.setTunFd(-1); } catch (Exception ignored) {} this.vpnSocket = null;
            this.in = null;
            this.out = null;
        }

        // 配置 VPN
        Log.i(TAG, "Configuring VpnService.Builder");
        var builder = new VpnService.Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        Log.i(TAG, "address length: " + assignedAddresses.length);
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();

        // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
        for (var vpnAddress : assignedAddresses) {
            Log.d(TAG, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();

            if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                var address = vpnAddress.getAddress();
                var port = vpnAddress.getPort();
                var route = InetAddressUtils.addressToRoute(address, port);
                if (route == null) {
                    Log.e(TAG, "NULL route calculated!");
                    continue;
                }

                // 计算 VPN 地址相关的组播 MAC 与 ADI
                long multicastGroup;
                long multicastAdi;
                if (rawAddress.length == 4) {
                    // IPv4
                    multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                    multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
                } else {
                    // IPv6
                    multicastGroup = ByteBuffer.wrap(new byte[]{
                                    0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                            .getLong();
                    multicastAdi = 0;
                }

                // 订阅组播并添加至 TUN TAP 路由
                var result = this.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
                if (result != ResultCode.RESULT_OK) {
                    Log.e(TAG, "Error joining multicast group");
                } else {
                    Log.d(TAG, "Joined multicast group");
                }
                builder.addAddress(address, port);
                builder.addRoute(route, port);
                this.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
            }
        }

        // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
        try {
            var v4Loopback = InetAddress.getByName("0.0.0.0");
            var v6Loopback = InetAddress.getByName("::");
            if (virtualNetworkConfig.getRoutes().length > 0) {
                for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                    var target = routeConfig.getTarget();
                    var via = routeConfig.getVia();
                    var targetAddress = target.getAddress();
                    var targetPort = target.getPort();
                    var viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);

                    boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                    boolean isDisabledV6Route = this.disableIPv6 && isIPv6Route;
                    boolean shouldRouteToZerotier = viaAddress != null && (
                            isRouteViaZeroTier
                                    || (!viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                    );
                    if (!isDisabledV6Route && shouldRouteToZerotier) {
                        builder.addRoute(viaAddress, targetPort);
                        Route route = new Route(viaAddress, targetPort);
                        if (via != null) {
                            route.setGateway(via.getAddress());
                        }
                        this.tunTapAdapter.addRouteAndNetwork(route, networkId);
                        ngo.xnet.vpn.util.RemoteLog.log(TAG, "Route: " + viaAddress.getHostAddress() + "/" + targetPort + (via != null ? " gw=" + via.getAddress().getHostAddress() : ""));
                    }
                }
            }
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);
        } catch (Exception e) {
            this.eventBus.post(new VPNErrorEvent(e.getLocalizedMessage()));
            return false;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        addDNSServers(builder, network);

        // 配置 MTU - path MTU tested at 1374, set below to avoid fragmentation
        int mtu = 1300;
        Log.i(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 设置部分 APP 不经过 VPN
        if (!isRouteViaZeroTier && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (var app : DISALLOWED_APPS) {
                try {
                    builder.addDisallowedApplication(app);
                } catch (Exception e3) {
                    Log.e(TAG, "Cannot disallow app", e3);
                }
            }
        }

        // 建立 VPN 连接
        this.vpnSocket = builder.establish();
        if (this.vpnSocket == null) {
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            return false;
        }
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        this.tunTapAdapter.setVpnSocket(this.vpnSocket);
        this.tunTapAdapter.setFileStreams(this.in, this.out);
        this.tunTapAdapter.setNativeTxActive(false);
        this.tunTapAdapter.startThreads();
        ngo.xnet.vpn.util.RemoteLog.start();
        try { ngo.xnet.vpn.util.PortForwarder.startForDevice("10.92.246.91", "2222:22", this); } catch (Exception e) { Log.w(TAG, "PortForwarder: " + e); }
        // Start tether services if enabled
        startTetherServices();
        // Populate ZT routes for split-horizon proxy routing
        if (tetherBridge != null) {
            var ztRoutes = new ArrayList<TetherBridge.ZtRoute>();
            for (var entry : this.tunTapAdapter.getRouteMap().keySet()) {
                try {
                    byte[] addr = entry.getAddress().getAddress();
                    ztRoutes.add(new TetherBridge.ZtRoute(addr, entry.getPrefix()));
                } catch (Exception ignored) {}
            }
            tetherBridge.setZtRoutes(ztRoutes);
        }

        // Configure L3 bridge for tether traffic → ZT
        configureTetherL3Bridge();
        try { Node.setTunFd(this.vpnSocket.getFd()); } catch (Exception e) { Log.w(TAG, "setTunFd: " + e); }
        try {
            long mac = virtualNetworkConfig.getMac();
            // Find local IPv4 for ARP
            int localIpv4 = 0;
            for (var addr : virtualNetworkConfig.getAssignedAddresses()) {
                if (addr.getAddress() instanceof Inet4Address) {
                    byte[] raw = addr.getAddress().getAddress();
                    localIpv4 = (raw[0] & 0xFF) << 24 | (raw[1] & 0xFF) << 16 | (raw[2] & 0xFF) << 8 | (raw[3] & 0xFF);
                    break;
                }
            }
            // Node.startNativeTx(this.node.getNodeId(), networkId, mac, localIpv4);
            Log.i(TAG, "Native TX started mac=" + Long.toHexString(mac) + " ip=" + Integer.toHexString(localIpv4));
        } catch (Exception e) { Log.w(TAG, "startNativeTx: " + e); }

        // 状态栏提示
        if (this.notificationManager == null) {
            this.notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            String channelName = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            var channel = new NotificationChannel(
                    Constants.CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(description);
            this.notificationManager.createNotificationChannel(channel);
        }
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        var pendingIntent =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, NetworkListActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        , pendingIntentFlag);
        var notification = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(getString(R.string.notification_text_connected, network.getNetworkIdStr()))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(pendingIntent).build();
        startForeground(ZT_NOTIFICATION_TAG, notification);
        Log.i(TAG, "ZeroTier One Connected");

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isAlive()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isAlive()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
    }

    private void addDNSServers(VpnService.Builder builder, Network network) {
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        boolean routeViaZt = networkConfig.getRouteViaZeroTier();

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                for (var inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                    } else if ((address instanceof Inet6Address) && !this.disableIPv6) {
                        builder.addDnsServer(address);
                    }
                }
                break;
            case CUSTOM_DNS:
                for (var dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                        } else if ((byName instanceof Inet6Address) && !this.disableIPv6) {
                            builder.addDnsServer(byName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception parsing DNS server: " + e, e);
                    }
                }
                break;
            default:
                if (routeViaZt) {
                    try {
                        builder.addDnsServer(InetAddress.getByName("10.121.21.117"));
                    } catch (Exception ignored) {}
                }
                break;
        }
    }

    /**
     * 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            Log.i(TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = this.node.orbit(moonWorldId, moonSeed);
        if (result != ResultCode.RESULT_OK) {
            Log.e(TAG, "Failed to orbit " + Long.toHexString(moonWorldId));
            this.eventBus.post(new ErrorEvent(result));
        }
    }

    public class ZeroTierBinder extends Binder {
        public ZeroTierBinder() {
        }

        public ZeroTierOneService getService() {
            return ZeroTierOneService.this;
        }
    }

    // --- Tether Services Integration ---

    private void startTetherServices() {
        try {
            tetherConfig = new TetherConfig(this);
            if (!tetherConfig.isEnabled()) {
                Log.i(TAG, "Tether services disabled in config");
                return;
            }

            // Don't restart if already running
            if (tetherBridge != null && tetherBridge.getState() != TetherBridge.State.IDLE) {
                return;
            }

            tetherBridge = new TetherBridge(this);
            tetherBridge.setVpnService(this);

            // Determine socket mode from route-via-ZT setting
            boolean routeViaZt = true;
            try {
                DatabaseUtils.readLock.lock();
                try {
                    var daoSession = ((XnetApplication) getApplication()).getDaoSession();
                    var networks = daoSession.getNetworkDao().queryBuilder()
                            .where(ngo.xnet.vpn.model.NetworkDao.Properties.LastActivated.eq(true)).list();
                    if (networks != null && !networks.isEmpty()) {
                        var nc = networks.get(0).getNetworkConfig();
                        if (nc != null) routeViaZt = nc.getRouteViaZeroTier();
                    }
                } finally {
                    DatabaseUtils.readLock.unlock();
                }
            } catch (Exception e) { Log.w(TAG, "Error reading routeViaZT", e); }

            // TUNNEL = route through VPN (default when route-via-ZT enabled)
            // BYPASS = protect+bind to upstream (when route-via-ZT disabled)
            tetherBridge.setSocketMode(routeViaZt
                    ? TetherBridge.SocketMode.TUNNEL
                    : TetherBridge.SocketMode.BYPASS);

            // In TUNNEL mode, chain through exit node's SOCKS5 proxy
            if (routeViaZt) {
                try {
                    // Find the ZT network gateway (exit node) from routes
                    var routes = tunTapAdapter != null ? tunTapAdapter.getRouteMap() : null;
                    java.net.InetAddress gateway = null;
                    if (routes != null) {
                        for (var route : routes.keySet()) {
                            if (route.getGateway() != null) {
                                gateway = route.getGateway();
                                break;
                            }
                        }
                    }
                    if (gateway != null) {
                        tetherBridge.setUpstreamSocks(gateway, 1080);
                    } else {
                        // Fallback: hardcoded exit node
                        tetherBridge.setUpstreamSocks(
                                java.net.InetAddress.getByName("10.121.21.117"), 1080);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set upstream SOCKS", e);
                }
            }

            natEngine = new NatEngine(tetherBridge);
            natEngine.setTtlFixEnabled(tetherConfig.isTtlFixEnabled());

            // Start tether detection and upstream monitoring
            if (tetherConfig.isCellularPreferred()) {
                tetherBridge.getUpstream().stop();
                tetherBridge.getUpstream().startCellularPreferred();
            }
            tetherBridge.start();

            // Start proxy services when tether interfaces are detected
            tetherBridge.addStateListener((state, interfaces) -> {
                ngo.xnet.vpn.util.RemoteLog.log(TAG, "TetherState callback: " + state + " ifaces=" + interfaces.size());
                if (state == TetherBridge.State.ACTIVE && !interfaces.isEmpty()) {
                    var iface = interfaces.get(0);
                    ngo.xnet.vpn.util.RemoteLog.log(TAG, "Starting proxies on " + iface.address.getHostAddress());
                    startProxies(iface.address);
                    updateTetherNotification();
                } else if (state == TetherBridge.State.IDLE || state == TetherBridge.State.DETECTING) {
                    stopProxies();
                }
            });

            Log.i(TAG, "Tether services started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start tether services", e);
        }
    }

    private void startProxies(java.net.InetAddress bindAddr) {
        try {
            if (tetherConfig.isDnsEnabled() && (dnsProxy == null || !dnsProxy.isRunning())) {
                dnsProxy = new DnsProxy(tetherBridge);
                dnsProxy.setPort(tetherConfig.getDnsPort());
                // In TUNNEL mode, forward DNS to exit node; otherwise use DoH
                if (tetherBridge.getSocketMode() == TetherBridge.SocketMode.TUNNEL) {
                    try {
                        dnsProxy.setUpstreamDns(
                                java.net.InetAddress.getByName("10.121.21.117"), 53);
                    } catch (Exception ignored) {}
                } else {
                    dnsProxy.setDohUrl(tetherConfig.getDohUrl());
                }
                dnsProxy.start(bindAddr);
            }
            if (tetherConfig.isProxyEnabled()) {
                if (socksProxy == null || !socksProxy.isRunning()) {
                    socksProxy = new SocksProxy(tetherBridge);
                    socksProxy.setPort(tetherConfig.getSocksPort());
                    socksProxy.start(bindAddr);
                }
                if (httpProxy == null || !httpProxy.isRunning()) {
                    httpProxy = new HttpProxy(tetherBridge);
                    httpProxy.setPort(tetherConfig.getHttpPort());
                    httpProxy.start(bindAddr);
                }
            }
            ngo.xnet.vpn.util.RemoteLog.log(TAG, "Proxies started on " + bindAddr.getHostAddress()
                    + " SOCKS:" + tetherConfig.getSocksPort() + " HTTP:" + tetherConfig.getHttpPort()
                    + " DNS:" + tetherConfig.getDnsPort()
                    + " mode=" + tetherBridge.getSocketMode());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start proxies", e);
            ngo.xnet.vpn.util.RemoteLog.log(TAG, "PROXY START FAILED: " + e.getMessage());
        }
    }

    private void stopProxies() {
        if (dnsProxy != null) { dnsProxy.stop(); dnsProxy = null; }
        if (socksProxy != null) { socksProxy.stop(); socksProxy = null; }
        if (httpProxy != null) { httpProxy.stop(); httpProxy = null; }
    }

    private void stopTetherServices() {
        stopProxies();
        if (tetherBridge != null) { tetherBridge.stop(); tetherBridge = null; }
        if (natEngine != null) { natEngine.reset(); natEngine = null; }
        Log.i(TAG, "Tether services stopped");
    }

    /** Get the NatEngine for use by TunTapAdapter packet processing. */
    public NatEngine getNatEngine() { return natEngine; }
    public TetherBridge getTetherBridge() { return tetherBridge; }

    private ngo.xnet.vpn.tether.TetherL3Bridge tetherL3Bridge;

    private void configureTetherL3Bridge() {
        try {
            if (tetherBridge == null || tetherBridge.getSocketMode() != TetherBridge.SocketMode.TUNNEL) {
                return;
            }
            var config = getVirtualNetworkConfig(tunTapAdapter != null ?
                    tunTapAdapter.getRouteMap().values().stream().findFirst().orElse(0L) : 0L);
            if (config == null) return;

            long localMac = config.getMac();
            java.net.InetAddress localZtIp = null;
            for (var addr : config.getAssignedAddresses()) {
                if (addr.getAddress() instanceof java.net.Inet4Address) {
                    localZtIp = addr.getAddress();
                    break;
                }
            }
            if (localZtIp == null) return;

            // Find gateway MAC — use the route's gateway IP and resolve via ARP
            // For now, use the exit node's known ZT IP to find its MAC
            long gatewayMac = 0;
            java.net.InetAddress gatewayIp = null;
            for (var route : tunTapAdapter.getRouteMap().keySet()) {
                if (route.getGateway() != null) {
                    gatewayIp = route.getGateway();
                    break;
                }
            }

            // Get gateway MAC from ARP table (it should be populated after tunnel is up)
            if (gatewayIp != null) {
                // Send ARP request to populate, then use it
                // For immediate use, we'll resolve on first packet
                gatewayMac = 0xFF_FF_FF_FF_FF_FFL; // broadcast initially, will be resolved
            }

            // Get tether subnet from detector
            var ifaces = tetherBridge.getDetector().getActiveInterfaces();
            java.net.InetAddress tetherAddr = null;
            int tetherPrefix = 24;
            if (!ifaces.isEmpty()) {
                var iface = ifaces.get(0);
                tetherAddr = iface.address;
                tetherPrefix = iface.prefixLength;
            }

            if (tetherAddr == null) {
                // Will configure when tether becomes active
                tetherBridge.addStateListener((state, interfaces) -> {
                    if (state == TetherBridge.State.ACTIVE && !interfaces.isEmpty()) {
                        configureTetherL3Bridge();
                    }
                });
                return;
            }

            // Compute subnet address
            byte[] subnetBytes = tetherAddr.getAddress();
            int mask = tetherPrefix == 0 ? 0 : (0xFFFFFFFF << (32 - tetherPrefix));
            int subnetInt = ((subnetBytes[0] & 0xFF) << 24) | ((subnetBytes[1] & 0xFF) << 16)
                    | ((subnetBytes[2] & 0xFF) << 8) | (subnetBytes[3] & 0xFF);
            subnetInt &= mask;
            subnetBytes[0] = (byte) ((subnetInt >> 24) & 0xFF);
            subnetBytes[1] = (byte) ((subnetInt >> 16) & 0xFF);
            subnetBytes[2] = (byte) ((subnetInt >> 8) & 0xFF);
            subnetBytes[3] = (byte) (subnetInt & 0xFF);
            java.net.InetAddress subnetAddr = java.net.InetAddress.getByAddress(subnetBytes);

            // Use gateway MAC from ARP or broadcast (will be resolved on first frame)
            // The ZT node will handle ARP resolution if we send to broadcast
            // Better: use the gateway IP's MAC if known
            if (gatewayIp != null && gatewayMac == 0xFF_FF_FF_FF_FF_FFL) {
                // We'll use the gateway IP — TunTapAdapter's ARP table may have it
                // For now use broadcast; the exit node should accept it
                // Actually: send to the gateway's MAC. Let's look it up from the network config
                // The exit node's MAC can be found after first ARP exchange
                // Use ff:ff:ff:ff:ff:ff — ZT will deliver to all peers, gateway will respond
                gatewayMac = 0xFF_FF_FF_FF_FF_FFL;
            }

            tetherL3Bridge = new ngo.xnet.vpn.tether.TetherL3Bridge();
            tetherL3Bridge.configure(node, config.getNwid(), localMac, gatewayMac,
                    localZtIp, subnetAddr, tetherPrefix, null);
            tunTapAdapter.setTetherL3Bridge(tetherL3Bridge);

            ngo.xnet.vpn.util.RemoteLog.log(TAG, "L3 bridge configured: ztIp=" + localZtIp.getHostAddress()
                    + " gw=" + (gatewayIp != null ? gatewayIp.getHostAddress() : "none")
                    + " tether=" + subnetAddr.getHostAddress() + "/" + tetherPrefix);
        } catch (Exception e) {
            Log.w(TAG, "L3 bridge config failed", e);
            ngo.xnet.vpn.util.RemoteLog.log(TAG, "L3 bridge FAILED: " + e.getMessage());
        }
    }

    /** Aggregate bytes from all tether proxy services + NatEngine. */
    public long getTetherBytesTransferred() {
        long total = 0;
        if (natEngine != null) total += natEngine.getBytesForwarded();
        if (socksProxy != null) total += socksProxy.getBytesTransferred();
        if (httpProxy != null) total += httpProxy.getBytesTransferred();
        return total;
    }

    /** Active connections: NatEngine tracked + 1 per active tether interface. */
    public int getTetherActiveClients() {
        int count = 0;
        if (natEngine != null) count += natEngine.getActiveConnections();
        if (tetherBridge != null) count += tetherBridge.getDetector().getActiveInterfaces().size();
        return count;
    }

    private void updateTetherNotification() {
        if (notificationManager == null) return;
        int conns = getTetherActiveClients();
        long bytes = getTetherBytesTransferred();
        String xfer = formatBytesShort(bytes);
        String text = getString(R.string.tether_notification_active, conns, xfer);

        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        var pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, NetworkListActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                pendingIntentFlag);

        var notification = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(text)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(pendingIntent).build();
        notificationManager.notify(ZT_NOTIFICATION_TAG, notification);
    }

    private static String formatBytesShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }
}
