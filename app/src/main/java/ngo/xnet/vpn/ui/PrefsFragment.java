package ngo.xnet.vpn.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import ngo.xnet.vpn.R;
import ngo.xnet.vpn.service.ZeroTierOneService;
import ngo.xnet.vpn.util.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;

public class PrefsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "PrefsFragment";
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == -1 && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) loadPlanetFromUri(uri);
                    }
                });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);

        findPreference("load_planet_file").setOnPreferenceClickListener(p -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(intent);
            return true;
        });

        findPreference("load_planet_url").setOnPreferenceClickListener(p -> {
            showUrlDialog();
            return true;
        });

        findPreference("reset_planet").setOnPreferenceClickListener(p -> {
            resetPlanet();
            return true;
        });
    }

    private void loadPlanetFromUri(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            writeMars(in);
            Toast.makeText(getContext(), R.string.load_planet, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load planet from file", e);
            Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show();
        }
    }

    private void showUrlDialog() {
        EditText input = new EditText(getContext());
        input.setHint("https://...");
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.enter_planet_file_url)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) loadPlanetFromUrl(url);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadPlanetFromUrl(String urlStr) {
        new Thread(() -> {
            try (InputStream in = new URL(urlStr).openStream()) {
                writeMars(in);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), R.string.load_planet, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Failed to load planet from URL", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), R.string.cannot_open_planet, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void resetPlanet() {
        try (InputStream in = requireContext().getAssets().open(Constants.FILE_MARS)) {
            writeMars(in);
            Toast.makeText(getContext(), R.string.reset_planet, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset planet", e);
        }
    }

    private synchronized void writeMars(InputStream in) throws Exception {
        File mars = new File(requireContext().getFilesDir(), Constants.FILE_MARS);
        try (FileOutputStream out = new FileOutputStream(mars)) {
            in.transferTo(out);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Constants.PREF_NETWORK_USE_CELLULAR_DATA.equals(key)) {
            if (sharedPreferences.getBoolean(key, false)) {
                requireActivity().startForegroundService(new Intent(getActivity(), ZeroTierOneService.class));
            }
        }
    }
}
