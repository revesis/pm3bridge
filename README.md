# PM3 Bridge

Android app that exports an OTG-attached **Proxmark3** from a **non-rooted**
phone over [usbwire](https://github.com/revesis) (a QUIC USB/IP transport), so a
Linux box on the LAN attaches it via `vhci` as `/dev/ttyACM0` and drives it with
the normal `proxmark3` client. No root and no pm3 client on the phone.

## How it works

```
proxmark3  ->  /dev/ttyACM0  ->  cdc-acm  ->  vhci  ->  usbwire-client (QUIC)
   -> WiFi ->  phone usbwire-server  ->  UsbManager fd  ->  Proxmark3 (USB-OTG)
```

The app opens the PM3 with the Android USB Host API (`UsbManager`, no root), gets
its usbdevfs fd from `UsbDeviceConnection.getFileDescriptor()`, and runs the
usbwire server **in-process** via JNI (`Native.startServer(fd, stateDir, listen)`),
which exports that one device with `usbwire-server --from-fd`.

## Build

Requires Android SDK (API 34, build-tools 34) and the prebuilt native lib.

```
# 1. Build the native lib from the usbwire tree (branch android-from-fd):
#    cargo build --release -p usbwire-server --target aarch64-linux-android
#    -> copy target/.../libusbwire_server.so to:
#       app/src/main/jniLibs/arm64-v8a/libusbwire_server.so
# 2. echo "sdk.dir=/path/to/android-sdk" > local.properties
# 3. gradle assembleDebug   ->  app/build/outputs/apk/debug/app-debug.apk
```

The prebuilt `libusbwire_server.so` (arm64-v8a) is committed so the app builds
out of the box; rebuild it from the usbwire source when that changes.

## Use

1. Phone: plug in the PM3 via OTG, open **PM3 Bridge**, allow USB. It shows
   `listening on 0.0.0.0:7443` and `opened 9ac4:4b8f fd=...`.
2. On a Linux box that can route to the phone (with usbip + cdc-acm):
   ```
   usbwire-client serve --server <phone-ip>:7443 --dev 9ac4:4b8f \
       --attach --trust-server --no-early-ack --no-push
   proxmark3 /dev/ttyACM0 -c "hw status"
   ```

VID/PID `9ac4:4b8f` is the Iceman/clone PM3; adjust in `MainActivity.java` and
`res/xml/device_filter.xml` for other devices.
