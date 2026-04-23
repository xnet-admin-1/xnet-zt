package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.util.Log;

import com.zerotier.sdk.DataStoreGetListener;
import com.zerotier.sdk.DataStorePutListener;

import net.kaaass.zerotierfix.util.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * ZeroTier file data store. Serves the custom planet (mars) when the core requests "planet".
 */
public class DataStore implements DataStoreGetListener, DataStorePutListener {

    private static final String TAG = "DataStore";
    private final Context context;

    public DataStore(Context context) {
        this.context = context;
    }

    @Override
    public int onDataStorePut(String name, byte[] buffer, boolean secure) {
        // Block writes to "planet" so the stock planet never overwrites mars
        if ("planet".equals(name)) {
            Log.d(TAG, "Blocked write to planet (using mars)");
            return 0;
        }
        Log.d(TAG, "Writing File: " + name);
        try {
            File target = new File(context.getFilesDir(), name);
            target.getParentFile().mkdirs();
            try (var out = new FileOutputStream(target)) {
                out.write(buffer);
            }
            return 0;
        } catch (IOException e) {
            Log.e(TAG, "Write failed: " + name, e);
            return -1;
        }
    }

    @Override
    public int onDelete(String name) {
        if ("planet".equals(name)) return 0;
        File f = new File(context.getFilesDir(), name);
        return (!f.exists() || f.delete()) ? 0 : 1;
    }

    @Override
    public long onDataStoreGet(String name, byte[] out_buffer) {
        // When core asks for "planet", serve our mars file instead
        if ("planet".equals(name)) {
            File mars = new File(context.getFilesDir(), Constants.FILE_MARS);
            if (mars.exists()) {
                Log.d(TAG, "Serving mars as planet");
                return readFileInto(mars, out_buffer);
            }
            Log.w(TAG, "Core asked for planet but mars not found");
        }
        return readFileInto(new File(context.getFilesDir(), name), out_buffer);
    }

    private long readFileInto(File file, byte[] buffer) {
        if (!file.exists()) return -1;
        try (var in = new FileInputStream(file)) {
            return in.read(buffer);
        } catch (IOException e) {
            Log.e(TAG, "Read failed: " + file.getName(), e);
            return -2;
        }
    }
}
