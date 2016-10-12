package hu.schrenk.blesdremotedroid.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;

import hu.schrenk.blesdremotedroid.util.ByteUtils;

public class UartGattCallback extends BluetoothGattCallback {

    private static final String TAG = "UartGattCallback";

    public static final int MESSAGE_BROWSE_COMPLETE = 10;
    public static final int FILE_DOWNLOAD_STARTED = 20;
    public static final int FILE_DOWNLOAD_IN_PROGRESS = 21;
    public static final int FILE_DOWNLOAD_FINISHED = 22;
    public static final int FILE_DELETE_FINISHED = 30;
    public static final int FILE_INFO_READY = 44;
    public static final int FILE_UPLOAD_STARTED = 51;
    public static final int FILE_UPLOAD_ERROR = 52;
    public static final int FILE_UPLOAD_FINISHED = 53;

    public static final int UART_TX_MAX_CHARACTERS = 20;

    // UUIDs for UART service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // UUID for the UART BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;

    private Handler replyMessageHandler;
    private UartMessageType messageType = UartMessageType.LIST;

    private volatile boolean connected = false;

    private StringBuffer receiveBuffer = new StringBuffer();

    //File download related variables
    private volatile boolean isDownloading = false;
    private File downloadFile;
    private FileOutputStream downloadFileStream;
    private Integer downloadFileSize = 0;
    private Integer downloadSizeReceived = 0;

    //File upload related variables
    private File uploadFile;

    public UartGattCallback(Handler replyMessageHandler) {
        super();
        this.replyMessageHandler = replyMessageHandler;
    }

    public boolean startDownload(File downloadFile) {
        this.downloadFile = downloadFile;
        try {
            boolean fileReady;
            if (this.downloadFile.exists()) {
                fileReady = this.downloadFile.delete();
                if (!fileReady) {
                    Log.e(TAG, downloadFile.getName() + " could not be deleted!");
                    return false;
                }
            }
            fileReady = this.downloadFile.createNewFile();
            if (!fileReady) {
                Log.e(TAG, downloadFile.getName() + " could not be created!");
                return false;
            }

            Log.i(TAG, "Download can be started for: " + this.downloadFile.getName());
            this.downloadFileStream = new FileOutputStream(this.downloadFile);
            this.isDownloading = false;
            this.downloadFileSize = 0;
            this.downloadSizeReceived = 0;
            this.receiveBuffer = new StringBuffer();
            return true;
        } catch (IOException ioe) {
            this.downloadFileStream = null;
            return false;
        }
    }

    public boolean startUpload(File uploadFile) {
        this.uploadFile = uploadFile;
        this.receiveBuffer = new StringBuffer();
        return true;
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
    }

    @Override
    public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        byte[] bytes = characteristic.getValue();
        if (bytes.length > 0) {
            switch (this.messageType) {
                case LIST: {
                    final String response = new String(bytes, Charset.forName("UTF-8"));

                    if (bytes.length > 2 && bytes[0] == 64 && bytes[bytes.length - 1] == 35) { // START and END present - @xxx#
                        this.receiveBuffer = new StringBuffer();
                        this.receiveBuffer = this.receiveBuffer.append(response);
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(0);
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(this.receiveBuffer.length() - 1);

                        this.sendMessage(MESSAGE_BROWSE_COMPLETE, this.receiveBuffer.toString());
                        Log.i(TAG, "Message received: " + this.receiveBuffer.toString());
                    } else if (bytes[0] == 64) { //@ - START without END
                        this.receiveBuffer = new StringBuffer();
                        this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(0);
                    } else if (bytes[bytes.length - 1] == 35) { //# - END without START
                        this.receiveBuffer = this.receiveBuffer.append(response).deleteCharAt(this.receiveBuffer.length() - 1);

                        sendMessage(MESSAGE_BROWSE_COMPLETE, this.receiveBuffer.toString());
                        Log.i(TAG, "Message received: " + this.receiveBuffer.toString());
                    } else { //In the middle - No START and END
                        this.receiveBuffer = this.receiveBuffer.append(response);
                    }
                } break;
                case INFO: {
                    final String response = new String(bytes, Charset.forName("UTF-8"));
                    if (ByteUtils.contains(bytes, (byte)64)) { //@ - START
                        this.receiveBuffer = new StringBuffer();
                    }
                    this.receiveBuffer = this.receiveBuffer.append(response);

                    if (ByteUtils.contains(bytes, (byte)35)) { //# - END
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(0);
                        this.receiveBuffer = this.receiveBuffer.deleteCharAt(this.receiveBuffer.length() - 1);

                        String[] messageParts = this.receiveBuffer.toString().split("%");
                        int fileSize = 0;
                        try {
                            fileSize = Integer.valueOf(messageParts[1]);
                        } catch (NumberFormatException nfe) {
                            Log.e(TAG, "Unable to parse file size: " + messageParts[1]);
                        }
                        Log.i(TAG, "INFO File name: " + messageParts[0]);
                        Log.i(TAG, "INFO File size: " + fileSize);
                        Log.i(TAG, "INFO Creation date: " + messageParts[2]);
                        Log.i(TAG, "INFO Modification date: " + messageParts[3]);

                        Message infoMessage = this.replyMessageHandler.obtainMessage(FILE_INFO_READY, null);
                        infoMessage.getData().putString("NAME", messageParts[0]);
                        infoMessage.getData().putInt("SIZE", fileSize);
                        infoMessage.getData().putString("CREATION_DATE", messageParts[2]);
                        infoMessage.getData().putString("MODIFICATION_DATE", messageParts[3]);
                        sendMessage(infoMessage);
                    }
                } break;
                case DELETE_FILE: {
                    this.receiveBuffer = this.receiveBuffer.append(new String(bytes, Charset.forName("UTF-8")));
                    if (this.receiveBuffer.toString().contains("#")) {
                        if (this.receiveBuffer.toString().contains("@OK%")) {
                            String fileName = this.receiveBuffer.substring(4, this.receiveBuffer.indexOf("#"));
                            Log.i(TAG, fileName + " was deleted.");
                            sendMessage(FILE_DELETE_FINISHED, fileName);
                        } else {
                            Log.e(TAG, "File delete error: " + this.receiveBuffer.toString());
                        }
                        this.receiveBuffer.delete(0, this.receiveBuffer.length()); //Clean the buffer
                    }
                } break;
                case GET_FILE: {
                    if (!isDownloading) {
                        this.receiveBuffer = this.receiveBuffer.append(new String(bytes, Charset.forName("UTF-8")));

                        if (this.receiveBuffer.toString().contains("#")) {
                            String fileSizeText = this.receiveBuffer.substring(1, this.receiveBuffer.indexOf("#"));
                            try {
                                this.downloadFileSize = Integer.valueOf(fileSizeText);
                                sendMessage(FILE_DOWNLOAD_STARTED, this.downloadFileSize);
                            } catch (NumberFormatException nfe) {
                                Log.e(TAG, "Unable to parse file size: " + fileSizeText);
                                this.downloadFileSize = 0;
                            }
                            Log.i(TAG, "File size: " + this.downloadFileSize);
                            this.receiveBuffer.delete(0, this.receiveBuffer.length()); //Clean the buffer

                            bytes = ByteUtils.subByteArray(bytes, ByteUtils.indexOf(bytes, (byte)35)); //#
                            this.isDownloading = true; //Switch to downloading mode
                        }
                    }

                    if (this.isDownloading) {
                        try {
                            if (this.downloadFileStream != null) {
                                this.downloadFileStream.write(bytes);
                                this.downloadFileStream.flush();
                                this.downloadSizeReceived += bytes.length;
                                sendMessage(FILE_DOWNLOAD_IN_PROGRESS, this.downloadSizeReceived);
                                Log.i(TAG, this.downloadSizeReceived + "/" + this.downloadFileSize);
                            }

                            if (this.downloadSizeReceived >= this.downloadFileSize) {
                                this.downloadFileStream.close();
                                this.downloadFileStream = null;
                                this.isDownloading = false;
                                sendMessage(FILE_DOWNLOAD_FINISHED, null);
                                Log.i(TAG, "File " + this.downloadFile.getName() + " was downloaded.");
                            }

                        } catch (IOException ioe) {
                            Log.e(TAG, "Error during file download operation.", ioe);
                            if (downloadFileStream != null) {
                                try {
                                    this.downloadFileStream.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Error during file download file stream close operation.", e);
                                } finally {
                                    this.downloadFileStream = null;
                                }
                            }
                        }
                    }
                } break;
                case PUT_FILE: {
                    this.receiveBuffer = this.receiveBuffer.append(new String(bytes, Charset.forName("UTF-8")));
                    if (this.receiveBuffer.toString().contains("@OK#")) {
                        this.sendMessage(FILE_UPLOAD_STARTED, this.uploadFile);
                    }
                    if (this.receiveBuffer.toString().contains("@KO#")) {
                        this.sendMessage(FILE_UPLOAD_ERROR, this.uploadFile);
                    }
                } break;
            }
        }
    }

    void sendMessage(int what, Object obj) {
        this.replyMessageHandler.sendMessage(this.replyMessageHandler.obtainMessage(what, obj));
    }

    void sendMessage(Message message) {
        this.replyMessageHandler.sendMessage(message);
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

            try {
                //Small break is necessary otherwise the UART connection doesn't notice it as another chunk
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error during data send." , e);
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

    public void send(BluetoothGatt gatt, byte[] data, UartMessageType uartMessageType) {
        if (data != null && data.length > 0) {
            this.messageType = uartMessageType;
            send(gatt, data);
        }
    }

}
