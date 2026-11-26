package com.pm3.bridge;

/** JNI bridge to the embedded usbwire server (libusbwire_server.so). */
public final class Native {
    static {
        System.loadLibrary("usbwire_server");
    }

    /**
     * Run the usbwire QUIC server in-process, exporting the device behind
     * {@code fd} (an already-open usbdevfs fd from
     * UsbDeviceConnection.getFileDescriptor()). Blocks until the process ends.
     *
     * @param fd       usbdevfs file descriptor (not dup'd; keep the
     *                 UsbDeviceConnection open for the lifetime of the call)
     * @param stateDir writable dir for config.json, identity and server.log
     * @param listen   bind address, e.g. "0.0.0.0:7443"; "" uses config.json
     * @return 0 on clean shutdown, negative on error
     */
    public static native int startServer(int fd, String stateDir, String listen);

    private Native() {}
}
