package hu.schrenk.blesdremotedroid.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Handler;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;

public class UartGattCallback extends BluetoothGattCallback {

    private static final String TAG = "UartGattCallback";

    public static final int MESSAGE_BROWSE_COMPLETE = 1;

    public static final int UART_TX_MAX_CHARACTERS = 20;

    // UUIDs for UART service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // UUID for the UART BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;

    private volatile boolean connected = false;

    private StringBuffer receiveBuffer = new StringBuffer();

    private Handler replyMessageHandler;

    private UartMessageType messageType = UartMessageType.LIST;

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
            switch (this.messageType) {
                case LIST: {
                    final String response = new String(bytes, Charset.forName("UTF-8"));

                    if (bytes.length > 2 && bytes[0] == 64 && bytes[bytes.length - 1] == 35) { // START and END present - @xxx#
                        this.receiveBuffer = new StringBuffer();
                        this.receiveBuffer = this.receiveBuffer.append(response);
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(0);
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(this.receiveBuffer.length() - 1);

                        this.replyMessageHandler.sendMessage(this.replyMessageHandler.obtainMessage(MESSAGE_BROWSE_COMPLETE, this.receiveBuffer.toString()));
                        Log.i(TAG, "Message received: " + this.receiveBuffer.toString());
                    } else if (bytes[0] == 64) { //@ - START without END
                        this.receiveBuffer = new StringBuffer();
                        this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(0);
                    } else if (bytes[bytes.length - 1] == 35) { //# - END without START
                        this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(this.receiveBuffer.length() - 1);

                        this.replyMessageHandler.sendMessage(this.replyMessageHandler.obtainMessage(MESSAGE_BROWSE_COMPLETE, this.receiveBuffer.toString()));
                        Log.i(TAG, "Message received: " + this.receiveBuffer.toString());
                    } else { //In the middle - No START and END
                        this.receiveBuffer = this.receiveBuffer.append(response);
                    }
                } break;
                case INFO: {
                    //TODO Implement INFO message handling
                } break;
                case DELETE_FILE: {
                    //TODO Implement DELETE FILE message handling
                } break;
                case GET_FILE: {
                    //TODO Implement GET FILE message handling
                } break;
                case PUT_FILE: {
                    //TODO Implement PUT FILE message handling
                } break;
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
        rx = null;
        tx = null;
        this.connected = false;
    }

    public boolean isConnected() {
        return this.connected;
    }

    private void send(BluetoothGatt gatt, byte[] data) {
        if (!connected || tx == null || data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }

        // Message has to be sent in chunks, because there is a UART_TX_MAX_CHARACTERS on the UART TX channel
        for (int i = 0; i < data.length; i += UART_TX_MAX_CHARACTERS) {
            final byte[] chunk = Arrays.copyOfRange(data, i, Math.min(i + UART_TX_MAX_CHARACTERS, data.length));
            tx.setValue(chunk);
            gatt.writeCharacteristic(tx);

            //Small break is necessary otherwise the UART connection doesn't notice it as another chunk
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void send(BluetoothGatt gatt, String data, UartMessageType uartMessageType) {
        if (data != null && !data.isEmpty()) {
            Log.i(TAG, "UART send: " + data);
            this.messageType = uartMessageType;
            send(gatt, data.getBytes(Charset.forName("UTF-8")));
        }
    }

}
