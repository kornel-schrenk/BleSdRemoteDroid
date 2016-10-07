package hu.schrenk.blesdremotedroid.ble;

import android.bluetooth.BluetoothGatt;
import android.os.AsyncTask;
import android.util.Log;

public class UartGattAsyncTask extends AsyncTask<String, Void, Void> {

    private static final String TAG = "UartGattAsyncTask";

    private UartMessageType messageType;
    private UartGattCallback uartGattCallback;
    private BluetoothGatt bluetoothGatt;

    public UartGattAsyncTask(UartMessageType messageType, UartGattCallback uartGattCallback, BluetoothGatt bluetoothGatt) {
        this(uartGattCallback, bluetoothGatt);
        this.setMessageType(messageType);
    }

    public UartGattAsyncTask(UartGattCallback uartGattCallback, BluetoothGatt bluetoothGatt) {
        super();
        this.uartGattCallback = uartGattCallback;
        this.bluetoothGatt = bluetoothGatt;
    }

    public void setMessageType(UartMessageType messageType) {
        this.messageType = messageType;
    }

    @Override
    protected Void doInBackground(String... params) {

        if (!this.uartGattCallback.isConnected()) {
            // Wait until the UART connection gets established
            while (!this.uartGattCallback.isConnected()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, e.getLocalizedMessage(), e);
                }
            }
        }

        this.uartGattCallback.send(this.bluetoothGatt, params[0], this.messageType);

        return null;
    }

}
