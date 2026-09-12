package io.github.fnyoat.tipismine.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import io.github.fnyoat.tipismine.hook.ExpressionContext;

/**
 * 模块 App 侧的表达式上下文——读取电池 / WiFi / 蓝牙等设备状态，用于 UI 实时预览。
 *
 * <p>与 {@link io.github.fnyoat.tipismine.hook.SystemUiExpressionContext} 逻辑一致，
 * 但在模块 App 进程中执行（UI 侧），避免跨进程通信。
 */
final class PreviewExpressionContext implements ExpressionContext {

    private final Context context;

    PreviewExpressionContext(Context context) {
        this.context = context;
    }

    @Override
    public String getVariable(String name) {
        try {
            switch (name) {
                case "battery":
                    return String.valueOf(getBatteryLevel());
                case "charging":
                    return String.valueOf(isCharging());
                case "wifi":
                    return getWifiSsid();
                case "bt":
                    return getBluetoothName();
                case "time":
                    return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                case "date":
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                default:
                    return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String executeGet(String url) {
        return "[GET " + url + "]";
    }

    @Override
    public String executePost(String url, String body) {
        return "[POST " + url + "]";
    }

    @Override
    public String executeGet(String url, long ttl) {
        return "[GET " + url + "]";
    }

    @Override
    public String executePost(String url, String body, long ttl) {
        return "[POST " + url + "]";
    }

    @Override
    public void clearCache() { }

    private int getBatteryLevel() {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return -1;
    }

    private boolean isCharging() {
        // 与 hook 侧一致：用 sticky 广播 ACTION_BATTERY_CHANGED 读取充电状态，
        // 避免 BatteryManager.isCharging() 在部分 ROM 上返回不可靠值。
        Intent b = context.getApplicationContext()
                .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private String getWifiSsid() {
        try {
            WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String ssid = info.getSSID();
            if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid != null ? ssid : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private String getBluetoothName() {
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            if (bm == null) return "";
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return "";
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return "";
            for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (name != null && !name.isEmpty()) return name;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }
}