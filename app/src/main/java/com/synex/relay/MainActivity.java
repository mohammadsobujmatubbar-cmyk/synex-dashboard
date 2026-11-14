package com.synex.relay;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import android.net.wifi.WifiManager;
import java.net.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private final String[] names = {"LIGHT", "FAN", "DC", "TV"};
    private final boolean[] states = new boolean[4];
    private Button[] buttons = new Button[4];
    private TextView connection, ipView;
    private DatagramSocket socket;
    private InetAddress deviceIP;
    private volatile boolean running = false;
    private ExecutorService executor;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();

        WifiManager wifi = (WifiManager) getApplicationContext()
            .getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("SYNEXRelay");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        executor = Executors.newSingleThreadExecutor();
        startNetwork();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 35, 24, 20);
        root.setBackgroundColor(Color.rgb(15, 22, 35));

        TextView title = new TextView(this);
        title.setText("SYNEX RELAY");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        connection = new TextView(this);
        connection.setText("Searching for ESP8266...");
        connection.setTextColor(Color.YELLOW);
        connection.setTextSize(16);
        connection.setGravity(Gravity.CENTER);
        root.addView(connection);

        ipView = new TextView(this);
        ipView.setText("Device IP: Not found");
        ipView.setTextColor(Color.LTGRAY);
        ipView.setGravity(Gravity.CENTER);
        root.addView(ipView);

        TextView info = new TextView(this);
        info.setText("\nLOCAL Wi-Fi CONTROL\n");
        info.setTextColor(Color.LTGRAY);
        info.setGravity(Gravity.CENTER);
        root.addView(info);

        for (int i = 0; i < 4; i++) {
            final int id = i;
            Button b = new Button(this);
            buttons[i] = b;
            b.setText(names[i] + "  |  OFF");
            b.setTextSize(17);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                if (deviceIP == null) {
                    Toast.makeText(this, "ESP8266 not found", Toast.LENGTH_SHORT).show();
                    return;
                }
                sendCommand(id, !states[id]);
            });
            root.addView(b);
        }

        Button search = new Button(this);
        search.setText("SEARCH DEVICE");
        search.setOnClickListener(v -> discover());
        root.addView(search);

        TextView footer = new TextView(this);
        footer.setText("\nSYNEX Relay V1.0");
        footer.setTextColor(Color.GRAY);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        updateButtons();
    }

    private void startNetwork() {
        running = true;
        executor.execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(4210));
                socket.setSoTimeout(1000);

                runOnUiThread(() ->
                    connection.setText("UDP Ready - Searching..."));

                long lastSearch = 0;

                while (running) {
                    if (System.currentTimeMillis() - lastSearch > 5000) {
                        sendPacket("SYNEX_SEARCH",
                            InetAddress.getByName("255.255.255.255"), 4210);
                        lastSearch = System.currentTimeMillis();
                    }

                    try {
                        byte[] data = new byte[256];
                        DatagramPacket packet =
                            new DatagramPacket(data, data.length);
                        socket.receive(packet);

                        String msg = new String(data, 0,
                            packet.getLength()).trim();

                        if (msg.startsWith("SYNEX_RELAY_V44,")) {
                            deviceIP = packet.getAddress();
                            final String address = deviceIP.getHostAddress();

                            runOnUiThread(() -> {
                                connection.setText("ESP8266 CONNECTED");
                                connection.setTextColor(Color.GREEN);
                                ipView.setText("Device IP: " + address);
                            });

                            sendPacket("SYNEX_GET_STATE", deviceIP, 4210);
                        }

                        if (msg.startsWith("SYNEX_STATE:")) {
                            String bits = msg.substring(12).trim();
                            if (bits.length() == 4) {
                                for (int i = 0; i < 4; i++) {
                                    states[i] = bits.charAt(i) == '1';
                                }
                                runOnUiThread(this::updateButtons);
                            }
                        }

                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() ->
                    connection.setText("UDP Error: " + e.getMessage()));
            }
        });
    }

    private void sendPacket(String message, InetAddress ip, int port) {
        try {
            if (socket == null || socket.isClosed()) return;
            byte[] data = message.getBytes();
            DatagramPacket packet =
                new DatagramPacket(data, data.length, ip, port);
            socket.send(packet);
        } catch (IOException e) {
            runOnUiThread(() ->
                Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void discover() {
        deviceIP = null;
        ipView.setText("Device IP: Searching...");
        connection.setText("Searching...");
        connection.setTextColor(Color.YELLOW);
        if (socket != null && !socket.isClosed()) {
            try {
                sendPacket("SYNEX_SEARCH",
                    InetAddress.getByName("255.255.255.255"), 4210);
            } catch (Exception ignored) {}
        }
    }

    private void sendCommand(int id, boolean on) {
        String value = on ? "ON" : "OFF";
        sendPacket("SYNEX_SET:" + (id + 1) + ":" + value,
            deviceIP, 4210);
    }

    private void updateButtons() {
        for (int i = 0; i < 4; i++) {
            buttons[i].setText(names[i] +
                (states[i] ? "  |  ON" : "  |  OFF"));
            buttons[i].setTextColor(Color.WHITE);
            buttons[i].setBackgroundColor(states[i]
                ? Color.rgb(20, 135, 75)
                : Color.rgb(65, 75, 95));
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        if (executor != null) executor.shutdownNow();
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        super.onDestroy();
    }
}
