# xnet-vpn

Android VPN client for XNet peer-to-peer networking.

## Overview

Hard fork of ZerotierFix, rewritten with a clean-room native layer. Based on Core 1.14.2, GPL-free — all dependencies are Apache 2.0 or BSD licensed.

Current version: **v2.5.0**

## Features

- ZeroTier networking over XNet
- Port forwarding with device discovery
- Speed test
- Remote logging
- **Tether Services** — share internet with tethered devices through VPN tunnel

## Tether Services

Integrated tethering support that bypasses carrier DUN restrictions and entitlement checks by routing tethered traffic through the VPN tunnel.

### How It Works

When USB tethering or Wi-Fi hotspot is enabled, xnet-vpn automatically:
1. Detects tether interfaces (USB/WiFi/Bluetooth/Ethernet)
2. Binds upstream to the default data network (bypasses DUN APN)
3. Fixes TTL to 64 (prevents carrier tether detection)
4. Starts proxy services on the tether interface

### Services

| Service | Port | Description |
|---------|------|-------------|
| DNS Proxy | 53 | DoH resolver (Cloudflare/Google), caching, split-horizon for ZeroTier names |
| SOCKS5 Proxy | 1080 | RFC 1928 compliant, CONNECT + UDP ASSOCIATE, optional auth |
| HTTP Proxy | 8080 | CONNECT tunnel + forward proxy, WPAD/PAC auto-discovery |

### Carrier Bypass

- **DUN bypass**: VPN upstream socket bound to default data network, not DUN interface
- **TTL fix**: Outbound tethered packets reset to TTL=64 (phone-native)
- **Entitlement skip**: Traffic flows through VPN tunnel, bypassing tethering framework entirely

### Configuration

Tether services are enabled by default. Settings available:
- Enable/disable tether bridge
- Enable/disable TTL fix
- Enable/disable proxy servers (SOCKS/HTTP/DNS)
- Configurable ports
- DoH upstream provider selection
- Optional SOCKS5 authentication

### Supported Tethering Modes

- USB (NCM/RNDIS)
- Wi-Fi Hotspot
- Bluetooth PAN
- USB-C Ethernet

### Architecture Reference

Proxy and network binding patterns informed by [TetherFi](https://github.com/pyamsoft/tetherfi) (MIT License).

## Build

Requires Android SDK and NDK.

```sh
./gradlew assembleDebug
```

## License

Apache 2.0
