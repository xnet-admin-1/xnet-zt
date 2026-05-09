package ngo.xnet.vpn.tether;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User preferences for tether services. Stored in SharedPreferences.
 */
public class TetherConfig {
    private static final String PREFS_NAME = "tether_config";
    private static final String KEY_ENABLED = "tether_enabled";
    private static final String KEY_TTL_FIX = "ttl_fix_enabled";
    private static final String KEY_PROXY_ENABLED = "proxy_enabled";
    private static final String KEY_DNS_ENABLED = "dns_enabled";
    private static final String KEY_SOCKS_PORT = "socks_port";
    private static final String KEY_HTTP_PORT = "http_port";
    private static final String KEY_DNS_PORT = "dns_port";
    private static final String KEY_DOH_URL = "doh_url";
    private static final String KEY_SOCKS_USER = "socks_user";
    private static final String KEY_SOCKS_PASS = "socks_pass";
    private static final String KEY_CELLULAR_PREFERRED = "cellular_preferred";

    private final SharedPreferences prefs;

    public TetherConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, true); }
    public void setEnabled(boolean v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); }

    public boolean isTtlFixEnabled() { return prefs.getBoolean(KEY_TTL_FIX, true); }
    public void setTtlFixEnabled(boolean v) { prefs.edit().putBoolean(KEY_TTL_FIX, v).apply(); }

    public boolean isProxyEnabled() { return prefs.getBoolean(KEY_PROXY_ENABLED, true); }
    public void setProxyEnabled(boolean v) { prefs.edit().putBoolean(KEY_PROXY_ENABLED, v).apply(); }

    public boolean isDnsEnabled() { return prefs.getBoolean(KEY_DNS_ENABLED, true); }
    public void setDnsEnabled(boolean v) { prefs.edit().putBoolean(KEY_DNS_ENABLED, v).apply(); }

    public int getSocksPort() { return getIntPref(KEY_SOCKS_PORT, 1080); }
    public void setSocksPort(int v) { prefs.edit().putString(KEY_SOCKS_PORT, String.valueOf(v)).apply(); }

    public int getHttpPort() { return getIntPref(KEY_HTTP_PORT, 8080); }
    public void setHttpPort(int v) { prefs.edit().putString(KEY_HTTP_PORT, String.valueOf(v)).apply(); }

    public int getDnsPort() { return getIntPref(KEY_DNS_PORT, 53); }
    public void setDnsPort(int v) { prefs.edit().putString(KEY_DNS_PORT, String.valueOf(v)).apply(); }

    private int getIntPref(String key, int def) {
        try { return Integer.parseInt(prefs.getString(key, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    public String getDohUrl() { return prefs.getString(KEY_DOH_URL, "https://1.1.1.1/dns-query"); }
    public void setDohUrl(String v) { prefs.edit().putString(KEY_DOH_URL, v).apply(); }

    public String getSocksUser() { return prefs.getString(KEY_SOCKS_USER, null); }
    public void setSocksUser(String v) { prefs.edit().putString(KEY_SOCKS_USER, v).apply(); }

    public String getSocksPass() { return prefs.getString(KEY_SOCKS_PASS, null); }
    public void setSocksPass(String v) { prefs.edit().putString(KEY_SOCKS_PASS, v).apply(); }

    public boolean isCellularPreferred() { return prefs.getBoolean(KEY_CELLULAR_PREFERRED, false); }
    public void setCellularPreferred(boolean v) { prefs.edit().putBoolean(KEY_CELLULAR_PREFERRED, v).apply(); }
}
