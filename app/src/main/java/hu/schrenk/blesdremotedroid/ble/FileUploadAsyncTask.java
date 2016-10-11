package hu.schrenk.blesdremotedroid.ble;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothGatt;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FileUploadAsyncTask extends AsyncTask<File, Void, Void> {

    private static final String TAG = "FileUploadAsyncTask";

    private UartMessageType messageType;
    private UartGattCallback uartGattCallback;
    private BluetoothGatt bluetoothGatt;
    private ProgressDialog loadingDialog;

    public FileUploadAsyncTask(UartMessageType messageType, UartGattCallback uartGattCallback, BluetoothGatt bluetoothGatt) {
        super();
        this.messageType = messageType;
        this.uartGattCallback = uartGattCallback;
        this.bluetoothGatt = bluetoothGatt;
    }

    @Override
    protected Void doInBackground(File... params) {
        byte[] uploadBuffer;
        long uploadSize = 0;
        long fileSize = params[0].length();
        try (FileInputStream fis = new FileInputStream(params[0])) {
            uploadBuffer = new byte[512];
            while (fis.read(uploadBuffer) != -1) {
                this.uartGattCallback.send(this.bluetoothGatt, uploadBuffer, this.messageType);
                uploadSize += 512;
                Log.i(TAG, uploadSize + "/" + fileSize);
            }
            this.uartGattCallback.sendMessage(UartGattCallback.FILE_UPLOAD_FINISHED, params[0]);
        } catch (IOException e) {
            Log.e(TAG, "File upload error.", e);
        }
        return null;
    }

    @Override
    protected void onPostExecute(Void aVoid) {
        super.onPostExecute(aVoid);
    }
}
