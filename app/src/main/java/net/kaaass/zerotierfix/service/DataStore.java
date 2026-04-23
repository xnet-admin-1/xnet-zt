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
 * ZeroTier file data store. Serves the mars file when the core requests it.
 */
public class DataStore implements DataStoreGetListener, DataStorePutListener {

    private static final String TAG = "DataStore";
    private final Context context;

    public DataStore(Context context) {
        this.context = context;
    }

    @Override
    public int onDataStorePut(String name, byte[] buffer, boolean secure) {
        Log.d(TAG, "Writing File: " + name + ", to: " + context.getFilesDir());
        try {
            File target = resolveFile(name);
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
        Log.d(TAG, "Deleting File: " + name);
        File f = resolveFile(name);
        return (!f.exists() || f.delete()) ? 0 : 1;
    }

    @Override
    public long onDataStoreGet(String name, byte[] out_buffer) {
        // Serve mars file when core asks for it
        if (Constants.FILE_MARS.equals(name)) {
            File mars = new File(context.getFilesDir(), Constants.FILE_MARS);
            if (mars.exists()) {
                Log.d(TAG, "Serving mars file");
                return readFileInto(mars, out_buffer);
            }
        }
        return readFileInto(resolveFile(name), out_buffer);
    }

    private File resolveFile(String name) {
        if (name.contains("/")) {
            return new File(context.getFilesDir(), name);
        }
        return new File(context.getFilesDir(), name);
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
