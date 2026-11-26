package com.pm3.bridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Opens the Proxmark3 over USB host (no root), then runs the embedded usbwire
 * QUIC server in-process over its fd. A usbwire client on the LAN (e.g. box92)
 * attaches it via vhci as /dev/ttyACM0 and drives it with the normal pm3 client.
 */
public class MainActivity extends Activity {

    static final int PM3_VID = 0x9ac4;   // Iceman / clone
    static final int PM3_PID = 0x4b8f;
    static final String LISTEN = "0.0.0.0:7443";
    static final String ACTION_PERM = "com.pm3.bridge.USB_PERMISSION";

    private UsbManager usb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView scroll;
    private File stateDir;
    private volatile boolean started = false;
    private UsbDeviceConnection conn;   // must stay open while the server runs
    private long logPos = 0;

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_PERM.equals(i.getAction())) return;
            UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && dev != null) openAndStart(dev);
            else append("USB permission denied");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        stateDir = new File(getFilesDir(), "usbwire");
        stateDir.mkdirs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        Button btn = new Button(this);
        btn.setText("Connect PM3 & start bridge");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        });
        root.addView(btn);

        TextView info = new TextView(this);
        info.setText("listen " + LISTEN + "   export 9ac4:* / 1050:*\n"
                + "then on box92:  usbwire-client attach ...  ->  /dev/ttyACM0");
        root.addView(info);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        scroll.addView(logView);
        root.addView(scroll);
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_PERM);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, f);
        }

        append("Ready. abi=" + Build.SUPPORTED_ABIS[0] + " sdk=" + Build.VERSION.SDK_INT);

        // Launched by USB_DEVICE_ATTACHED? That device is already permitted.
        UsbDevice attached = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (attached != null) openAndStart(attached);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        UsbDevice attached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (attached != null) openAndStart(attached);
    }

    private void connect() {
        UsbDevice dev = findPm3();
        if (dev == null) {
            append("PM3 not found (vid 9ac4 pid 4b8f). Plug it in via OTG.");
            return;
        }
        if (usb.hasPermission(dev)) {
            openAndStart(dev);
        } else {
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent i = new Intent(ACTION_PERM).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
            usb.requestPermission(dev, pi);
            append("Requesting USB permission...");
        }
    }

    private UsbDevice findPm3() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == PM3_VID && d.getProductId() == PM3_PID) return d;
        }
        return null;
    }

    private synchronized void openAndStart(UsbDevice dev) {
        if (started) { append("bridge already running"); return; }
        UsbDeviceConnection c = usb.openDevice(dev);
        if (c == null) { append("openDevice() failed"); return; }
        conn = c;
        final int fd = c.getFileDescriptor();
        append("opened " + String.format("%04x:%04x", dev.getVendorId(), dev.getProductId())
                + "  fd=" + fd);
        try {
            writeConfig();
        } catch (IOException e) {
            append("writing config.json failed: " + e);
            return;
        }
        started = true;
        final String sd = stateDir.getAbsolutePath();
        new Thread(new Runnable() {
            @Override public void run() {
                append("usbwire server starting (in-process)...");
                int rc = Native.startServer(fd, sd, LISTEN);
                append("usbwire server exited rc=" + rc);
                started = false;
            }
        }, "usbwire").start();
        tailLog();
    }

    private void writeConfig() throws IOException {
        String json = "{\n"
                + "  \"listen\": \"" + LISTEN + "\",\n"
                + "  \"export\": [\"9ac4:*\", \"1050:*\"],\n"
                + "  \"restore_on_release\": false,\n"
                + "  \"restore_delay_secs\": 5,\n"
                + "  \"trusted_subnets\": [\"192.168.0.0/16\", \"10.0.0.0/8\", \"172.16.0.0/12\"]\n"
                + "}\n";
        File cfg = new File(stateDir, "config.json");
        try (FileOutputStream o = new FileOutputStream(cfg)) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void tailLog() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                File lf = new File(stateDir, "server.log");
                try {
                    if (lf.exists()) {
                        try (RandomAccessFile r = new RandomAccessFile(lf, "r")) {
                            if (r.length() < logPos) logPos = 0;
                            r.seek(logPos);
                            int n = (int) (r.length() - logPos);
                            if (n > 0) {
                                byte[] buf = new byte[n];
                                r.readFully(buf);
                                logPos = r.length();
                                appendUi(new String(buf, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (IOException ignored) {}
                ui.postDelayed(this, 1000);
            }
        }, 500);
    }

    private void append(final String s) {
        ui.post(new Runnable() {
            @Override public void run() { appendUi(s + "\n"); }
        });
    }

    private void appendUi(String s) {
        logView.append(s);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(permReceiver); } catch (Exception ignored) {}
    }
}
