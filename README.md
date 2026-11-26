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

**Workflow:** develop anywhere; build the APK in a container on the build host.

```
./build.sh                 # docker build + gradle assembleDebug (no host SDK)
#  -> app/build/outputs/apk/debug/app-debug.apk
```

`build.sh` builds the image in `docker/` (JDK 21 + Android SDK 34 + Gradle 8.9)
and runs Gradle inside it with the repo mounted, so nothing is installed on the
host. It uses **podman or docker** automatically (override with
`CONTAINER_ENGINE=docker ./build.sh`). The prebuilt `libusbwire_server.so`
(arm64-v8a) is committed, so the build only packages it.

The native lib itself comes from the usbwire tree (branch `android-from-fd`):

```
cargo build --release -p usbwire-server --target aarch64-linux-android
# copy target/.../libusbwire_server.so -> app/src/main/jniLibs/arm64-v8a/
```

Rebuild and recommit it when the usbwire server changes.

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
