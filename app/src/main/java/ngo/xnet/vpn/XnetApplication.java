package ngo.xnet.vpn;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import ngo.xnet.vpn.model.DaoMaster;
import ngo.xnet.vpn.model.DaoSession;
import ngo.xnet.vpn.model.ZTOpenHelper;
import ngo.xnet.vpn.util.Constants;

import java.io.File;
import java.io.FileOutputStream;

public class XnetApplication extends MultiDexApplication {
    private DaoSession mDaoSession;

    public void onCreate() {
        super.onCreate();
        Log.i("Application", "Starting Application");
        this.mDaoSession = new DaoMaster(
                new ZTOpenHelper(this, "ztfixdb", null)
                        .getWritableDatabase()
        ).newSession();
        installMars();
    }

    private void installMars() {
        File marsFile = new File(getFilesDir(), Constants.FILE_MARS);
        // Install from asset first so we always have a working mars
        installMarsFromAsset(marsFile);
        // Then fetch latest from server in background (overwrites asset version)
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://mars.xnet.ngo/api/mars");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                File tmp = new File(getFilesDir(), "mars.tmp");
                try (var in = c.getInputStream(); var out = new FileOutputStream(tmp)) {
                    in.transferTo(out);
                }
                if (tmp.length() > 0) {
                    tmp.renameTo(marsFile);
                    Log.i("Application", "Mars updated from server");
                }
            } catch (Exception e) {
                Log.w("Application", "Mars fetch failed: " + e.getMessage());
            }
        }).start();
    }

    private void installMarsFromAsset(File marsFile) {
        try (var in = getAssets().open(Constants.FILE_MARS);
             var out = new FileOutputStream(marsFile)) {
            in.transferTo(out);
            Log.i("Application", "Mars installed from asset");
        } catch (Exception e) {
            Log.e("Application", "Mars asset install failed: " + e.getMessage());
        }
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
}
