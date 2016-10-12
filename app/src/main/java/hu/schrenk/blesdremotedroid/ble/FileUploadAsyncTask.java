package hu.schrenk.blesdremotedroid.ble;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothGatt;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import hu.schrenk.blesdremotedroid.util.ByteUtils;

public class FileUploadAsyncTask extends AsyncTask<File, Integer, Void> {

    private static final String TAG = "FileUploadAsyncTask";

    private static final int READ_BUFFER_SIZE = 512;

    private UartMessageType messageType;
    private UartGattCallback uartGattCallback;
    private BluetoothGatt bluetoothGatt;
    private ProgressDialog progressDialog;

    public FileUploadAsyncTask(UartMessageType messageType, UartGattCallback uartGattCallback, BluetoothGatt bluetoothGatt) {
        super();
        this.messageType = messageType;
        this.uartGattCallback = uartGattCallback;
        this.bluetoothGatt = bluetoothGatt;
    }

    public void setProgressDialog(ProgressDialog progressDialog) {
        this.progressDialog = progressDialog;
    }

    @Override
    protected Void doInBackground(File... params) {
        byte[] uploadBuffer;
        int uploadSize = 0;
        int fileSize = (int)params[0].length();
        try (FileInputStream fis = new FileInputStream(params[0])) {
            uploadBuffer = new byte[READ_BUFFER_SIZE];
            int readSize = fis.read(uploadBuffer);
            while (readSize != -1) {
                if (readSize == READ_BUFFER_SIZE) {
                    this.uartGattCallback.send(this.bluetoothGatt, uploadBuffer, this.messageType);
                } else {
                    this.uartGattCallback.send(this.bluetoothGatt, ByteUtils.truncByteArray(uploadBuffer, readSize), this.messageType);
                    //Make Bluefruit to flush and close the stream
                    this.uartGattCallback.send(this.bluetoothGatt, "\r", this.messageType);
                }
                uploadSize += readSize;
                readSize = fis.read(uploadBuffer);

                Log.i(TAG, uploadSize + "/" + fileSize);
                this.publishProgress(uploadSize);
            }
            this.uartGattCallback.sendMessage(UartGattCallback.FILE_UPLOAD_FINISHED, params[0]);
        } catch (IOException e) {
            Log.e(TAG, "File upload error.", e);
        } finally {
            try {
                Thread.sleep(1000); //Wait until the UART line settles
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        super.onProgressUpdate(values);
        if (progressDialog != null) {
            progressDialog.setProgress(values[0]);
        }
    }

}
