package net.kaaass.zerotierfix;

import android.util.Log;

import androidx.multidex.MultiDexApplication;

import net.kaaass.zerotierfix.model.DaoMaster;
import net.kaaass.zerotierfix.model.DaoSession;
import net.kaaass.zerotierfix.model.ZTOpenHelper;
import net.kaaass.zerotierfix.util.Constants;

import java.io.File;
import java.io.FileOutputStream;

public class ZerotierFixApplication extends MultiDexApplication {
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
        if (marsFile.exists()) return;
        try (var in = getAssets().open(Constants.FILE_MARS);
             var out = new FileOutputStream(marsFile)) {
            in.transferTo(out);
            Log.i("Application", "Mars installed");
        } catch (Exception e) {
            Log.e("Application", "Mars install failed: " + e.getMessage());
        }
    }

    public DaoSession getDaoSession() {
        return this.mDaoSession;
    }
}
