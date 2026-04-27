package ngo.xnet.vpn.util;

import java.math.BigInteger;

/**
 * 网络号处理工具类
 */
public class NetworkIdUtils {
    public static long hexStringToLong(String str) {
        return new BigInteger(str, 16).longValue();
    }
}
