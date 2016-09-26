package hu.schrenk.blesdremotedroid.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Handler;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.UUID;

public class UartGattCallback extends BluetoothGattCallback {

    private static final String TAG = "UartGattCallback";

    public static final int MESSAGE_BROWSE_COMPLETE = 1;

    // UUIDs for UART service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // UUID for the UART BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;

    private volatile boolean connected = false;
    private volatile boolean writeInProgress = false;

    private StringBuffer receiveBuffer = new StringBuffer();

    private char[] hexArray = "0123456789ABCDEF".toCharArray();

    private Handler replyMessageHandler;

    public UartGattCallback(Handler replyMessageHandler) {
        super();
        this.replyMessageHandler = replyMessageHandler;
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Connected to device, start discovering services.
                if (!gatt.discoverServices()) {
                    // Error starting service discovery.
                    connectFailure();
                }
            }
            else {
                // Error connecting to device.
                connectFailure();
            }
        }
        else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            // Disconnected, notify callbacks of disconnection.
            rx = null;
            tx = null;
            this.connected = false;
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        // Notify connection failure if service discovery failed.
        if (status == BluetoothGatt.GATT_FAILURE) {
            connectFailure();
            return;
        }

        // Save reference to each UART characteristic.
        tx = gatt.getService(UART_UUID).getCharacteristic(TX_UUID);
        rx = gatt.getService(UART_UUID).getCharacteristic(RX_UUID);

        // Setup notifications on RX characteristic changes (i.e. data received).
        // First call setCharacteristicNotification to enable notification.
        if (!gatt.setCharacteristicNotification(rx, true)) {
            // Stop if the characteristic notification setup failed.
            connectFailure();
            return;
        }

        // Next update the RX characteristic's client descriptor to enable notifications.
        BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
        if (desc == null) {
            // Stop if the RX characteristic has no client descriptor.
            connectFailure();
            return;
        }
        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(desc)) {
            // Stop if the client descriptor could not be written.
            connectFailure();
            return;
        }
        // Notify of connection completion.
        this.connected = true;
        Log.i(TAG, "UART service was connected.");
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "UART write successful");
        }
        writeInProgress = false;
    }

    @Override
    public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);

        Log.i(TAG, "Characteristic read status: " + status);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        final byte[] bytes = characteristic.getValue();
        if (bytes.length > 0) {
            final String response = new String(bytes, Charset.forName("UTF-8"));

            if (bytes[0] == 64) { //@ - START
                this.receiveBuffer = new StringBuffer();
                this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(0);
            } else if (bytes[bytes.length-1] == 35) { //# - END
                this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(this.receiveBuffer.length()-1);

                //Send a message about that the complete browse reply was received
                this.replyMessageHandler.sendMessage(this.replyMessageHandler.obtainMessage(MESSAGE_BROWSE_COMPLETE, this.receiveBuffer.toString()));
                Log.i(TAG, "Complete message: " + this.receiveBuffer.toString());
            } else {
                this.receiveBuffer = this.receiveBuffer.append(response);
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
        rx = null;
        tx = null;
        this.connected = false;

        //TODO Open ScanActivity
    }

    public boolean isConnected() {
        return this.connected;
    }

    // Send data to connected UART device.
    public void send(BluetoothGatt gatt, byte[] data) {
        if (!connected || tx == null || data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        tx.setValue(data);
        writeInProgress = true; // Set the write in progress flag
        gatt.writeCharacteristic(tx);
        // ToDo: Update to include a timeout in case this goes into the weeds
        while (writeInProgress); // Wait for the flag to clear in onCharacteristicWrite
    }

/*    protected void sendData(byte[] data) {
        if (mUartService != null) {
            // Split the value into chunks (UART service has a maximum number of characters that can be written )
            for (int i = 0; i < data.length; i += kTxMaxCharacters) {
                final byte[] chunk = Arrays.copyOfRange(data, i, Math.min(i + kTxMaxCharacters, data.length));
                mBleManager.writeService(mUartService, UUID_TX, chunk);
            }
        } else {
            Log.w(TAG, "Uart Service not discovered. Unable to send data");
        }
    }*/

    // Send data to connected UART device.
    public void send(BluetoothGatt gatt, String data) {
        if (data != null && !data.isEmpty()) {
            Log.i(TAG, "UART send: " + data);
            send(gatt, data.getBytes(Charset.forName("UTF-8")));
        }
    }

    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
