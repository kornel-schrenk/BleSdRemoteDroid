package hu.schrenk.blesdremotedroid;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

import hu.schrenk.blesdremotedroid.ble.BluetoothDeviceData;

import static hu.schrenk.blesdremotedroid.ble.BluetoothDeviceData.TYPE_BEACON;
import static hu.schrenk.blesdremotedroid.ble.BluetoothDeviceData.TYPE_UART;
import static hu.schrenk.blesdremotedroid.ble.BluetoothDeviceData.TYPE_URI_BEACON;

public class ScanActivity extends AppCompatActivity implements AdapterView.OnItemClickListener{

    private static final String TAG = "ScanActivity";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 1;

    // Stops scanning after 6 seconds.
    private static final long SCAN_PERIOD = 6000;

    private ListView bleDevicesListView;

    private BleDeviceListAdapter bleDeviceListAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private Handler bleScanHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, R.string.ble_scan_started, Snackbar.LENGTH_LONG).show();
                scanBleDevice();
            }
        });

        // Use this check to determine whether BLE is supported on the device.  Then you can selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        // Android M Permission check 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
            }
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        this.bleScanHandler = new Handler();
        this.bleDevicesListView = (ListView)findViewById(R.id.bleDevicesListView);
        this.bleDevicesListView.setOnItemClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!bluetoothAdapter.isEnabled()) {
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }

        // Initializes list view adapter.
        this.bleDeviceListAdapter = new BleDeviceListAdapter();
        this.bleDevicesListView.setAdapter(this.bleDeviceListAdapter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.bleDeviceListAdapter.clear();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_scan, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_exit) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_COARSE_LOCATION: {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "COARSE_LOCATION permission granted");
                } else {
                    Toast.makeText(this, R.string.not_granted_permission, Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        }
    }

    private void scanBleDevice() {
        this.bleScanHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Stops scanning after a pre-defined scan period.
                bluetoothAdapter.stopLeScan(bleScanCallback);
                Log.i(TAG, "BLE device scan was stopped.");
            }
        }, SCAN_PERIOD);

        //Remove the previously added Ble devices
        this.bleDeviceListAdapter.clear();
        bluetoothAdapter.startLeScan(bleScanCallback);
        Log.i(TAG, "BLE device scan was started.");
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        BluetoothDeviceData bluetoothDeviceData = (BluetoothDeviceData)this.bleDeviceListAdapter.getItem(position);
        String deviceName = bluetoothDeviceData.getName();
        if (deviceName == null || "".equals(deviceName)) {
            deviceName = ScanActivity.this.getString(R.string.unknown_device);
        }
        Log.i(TAG, "BLE device was selected: " + deviceName);
    }

    private class BleDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDeviceData> bleDevices;

        public BleDeviceListAdapter() {
            super();
            this.bleDevices = new ArrayList<BluetoothDeviceData>();
        }

        public void addDevice(BluetoothDeviceData deviceData) {
            //Filter out previously found same devices
            boolean contains = false;
            for (BluetoothDeviceData bleDeviceData : this.bleDevices) {
                if (bleDeviceData.getDevice().getAddress().equals(deviceData.getDevice().getAddress())) {
                    contains = true;
                }
            }
            if (!contains) {
                this.bleDevices.add(deviceData);
            }
        }

        public void clear() {
            bleDevices.clear();
        }

        @Override
        public int getCount() {
            return bleDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return bleDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup parent) {
            ViewHolder viewHolder;

            if (view == null) {
                LayoutInflater inflater = ScanActivity.this.getLayoutInflater();
                view = inflater.inflate(R.layout.listitem_device, parent, false);
                viewHolder = new ViewHolder();
                viewHolder.deviceNameTextView = (TextView) view.findViewById(R.id.deviceNameTextView);
                viewHolder.deviceAddressTextView = (TextView) view.findViewById(R.id.deviceAddressTextView);
                viewHolder.rssiImageView = (ImageView) view.findViewById(R.id.rssiImageView);
                viewHolder.rssiTextView = (TextView) view.findViewById(R.id.rssiTextView);
                viewHolder.typeImageView = (ImageView) view.findViewById(R.id.typeImageView);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder)view.getTag();
            }

            BluetoothDeviceData deviceData = bleDevices.get(i);

            String deviceName = deviceData.getName();
            if (deviceName == null || "".equals(deviceName)) {
                deviceName = ScanActivity.this.getString(R.string.unknown_device);
            }

            viewHolder.deviceNameTextView.setText(deviceName);
            viewHolder.deviceAddressTextView.setText(deviceData.getAddress());
            viewHolder.rssiTextView.setText(deviceData.getRssi() == 127 ? getString(R.string.rssi_not_available) : String.valueOf(deviceData.getRssi()));
            viewHolder.rssiImageView.setImageResource(this.getDrawableIdForRssi(deviceData.getRssi()));
            viewHolder.typeImageView.setImageResource(this.getDrawableIdForType(deviceData.getType()));

            return view;
        }

        private int getDrawableIdForRssi(int rssi) {
            int index;
            if (rssi == 127 || rssi <= -84) {       // 127 reserved for RSSI not available
                index = 0;
            } else if (rssi <= -72) {
                index = 1;
            } else if (rssi <= -60) {
                index = 2;
            } else if (rssi <= -48) {
                index = 3;
            } else {
                index = 4;
            }

            final int kSignalDrawables[] = {
                    R.drawable.signalstrength0,
                    R.drawable.signalstrength1,
                    R.drawable.signalstrength2,
                    R.drawable.signalstrength3,
                    R.drawable.signalstrength4};
            return kSignalDrawables[index];
        }

        private int getDrawableIdForType(int type) {
            if (type == TYPE_UART) {
                return R.drawable.type_uart;
            } else if (type == TYPE_BEACON) {
                return R.drawable.type_beacon;
            } else if (type == TYPE_URI_BEACON) {
                return R.drawable.type_uri_beacon;
            }
            return R.drawable.type_unknown;
        }
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback bleScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bleDeviceListAdapter.addDevice(new BluetoothDeviceData(device, rssi, scanRecord));
                            bleDeviceListAdapter.notifyDataSetChanged();
                        }
                    });
                }
            };

    static class ViewHolder {
        TextView deviceNameTextView;
        TextView deviceAddressTextView;
        ImageView rssiImageView;
        TextView rssiTextView;
        ImageView typeImageView;
    }
}
