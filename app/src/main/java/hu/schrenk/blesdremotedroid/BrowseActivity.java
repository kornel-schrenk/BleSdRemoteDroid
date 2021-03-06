package hu.schrenk.blesdremotedroid;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.nononsenseapps.filepicker.FilePickerActivity;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Stack;

import hu.schrenk.blesdremotedroid.ble.FileUploadAsyncTask;
import hu.schrenk.blesdremotedroid.ble.UartGattAsyncTask;
import hu.schrenk.blesdremotedroid.ble.UartGattCallback;
import hu.schrenk.blesdremotedroid.ble.UartMessageType;

public class BrowseActivity extends AppCompatActivity implements AdapterView.OnItemClickListener{

    private static final String TAG = "BrowseActivity";

    private static final int FILE_DOWNLOAD_CODE = 456;
    private static final int FILE_UPLOAD_CODE = 789;

    private BluetoothDevice bluetoothDevice;
    private BluetoothGatt bluetoothGatt;
    private UartGattCallback uartGattCallback;
    private Handler browseMessageHandler;

    private ListView nodesListView;
    private NodesListAdapter nodesListAdapter;
    private ProgressDialog loadingDialog;
    private ProgressDialog transferDialog;

    private String currentPath = ""; //ROOT

    private Stack<File> downloadDestinationFilesStack = new Stack<>();
    private Stack<String> deleteDestinationFilesStack = new Stack<>();

    //TODO Change this to a stack
    private File uploadFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        this.getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        this.loadingDialog = new ProgressDialog(this);
        this.loadingDialog.setIndeterminate(true);
        this.loadingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        this.loadingDialog.setMessage(getString(R.string.dialog_loading));
        this.loadingDialog.setCancelable(true);

        this.bluetoothDevice = this.getIntent().getParcelableExtra("BluetoothDevice");
        Log.i(TAG, "Device address: " + bluetoothDevice.getAddress());

        //Handle the message in the UI thread
        this.browseMessageHandler = new BrowseMessageHandler(Looper.getMainLooper());
        this.uartGattCallback = new UartGattCallback(this.browseMessageHandler);
        this.bluetoothGatt = this.bluetoothDevice.connectGatt(this, false, this.uartGattCallback);

        this.nodesListView = (ListView)findViewById(R.id.nodesListView);

        this.nodesListAdapter = new BrowseActivity.NodesListAdapter();
        this.nodesListView.setAdapter(this.nodesListAdapter);

        this.nodesListView.setOnItemClickListener(this);

        this.currentPath = this.sendListDirectory(""); //ROOT
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (this.bluetoothGatt != null) {
            this.bluetoothGatt.disconnect();
            this.bluetoothGatt.close();
            Log.i(TAG, "Bluetooth LE connection was closed.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (this.loadingDialog.isShowing()) {
            this.loadingDialog.dismiss();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_browse, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_download) {
            String downloadDirectory = Environment.getRootDirectory().getPath();
            Log.i(TAG, "Root directory: " + downloadDirectory);

            // This always works
            //Intent i = new Intent(this, FilePickerActivity.class);
            // This works if you defined the intent filter
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);

            // Set these depending on your use case. These are the defaults.
            i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
            i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
            i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR);

            // Configure initial directory by specifying a String.
            // You could specify a String like "/storage/emulated/0/", but that can
            // dangerous. Always use Android's API calls to get paths to the SD-card or
            // internal memory.
            i.putExtra(FilePickerActivity.EXTRA_START_PATH, Environment.getExternalStorageDirectory().getPath());

            startActivityForResult(i, FILE_DOWNLOAD_CODE);
            return true;
        } else if (id == R.id.action_delete) {
            this.deleteDestinationFilesStack.clear();
            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (node.isSelected && !node.isDirectory && !node.isLevelUp) {
                    String fileName;
                    if ("".equals(currentPath)) {
                        fileName = node.name;
                    } else {
                        fileName = this.currentPath + "/" + node.name;
                    }

                    this.deleteDestinationFilesStack.push(fileName);
                }
            }

            if (!this.deleteDestinationFilesStack.isEmpty()) {
                loadingDialog.setMessage(getString(R.string.dialog_deleting));
                loadingDialog.show();

                //Delete the first file in the row
                String fileName = this.deleteDestinationFilesStack.pop();
                Log.i(TAG, "Delete file: " + fileName);
                this.startFileDelete(fileName);
            }

            return true;
        } else if (id == R.id.action_upload) {
            String downloadDirectory = Environment.getRootDirectory().getPath();
            Log.i(TAG, "Root directory: " + downloadDirectory);

            Intent i = new Intent(Intent.ACTION_GET_CONTENT);

            // Set these depending on your use case. These are the defaults.
            i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
            i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false);
            i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_FILE);

            i.putExtra(FilePickerActivity.EXTRA_START_PATH, Environment.getExternalStorageDirectory().getPath());

            startActivityForResult(i, FILE_UPLOAD_CODE);
        } else if (id == R.id.action_select_all) {
            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (!node.isDirectory && !node.isLevelUp) {
                    node.isSelected = true;
                }
            }
            this.nodesListAdapter.notifyDataSetChanged();
            return true;
        } else if (id == R.id.action_unselect_all) {
            unselectAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void unselectAll() {
        for (FileSystemNode node : this.nodesListAdapter.nodes()) {
            if (!node.isDirectory && !node.isLevelUp) {
                node.isSelected = false;
            }
        }
        this.nodesListAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_DOWNLOAD_CODE && resultCode == Activity.RESULT_OK) {
            Uri directoryUri = data.getData();
            Log.i(TAG, "Selected download directory: " + new File(directoryUri.getPath()).getPath());

            this.downloadDestinationFilesStack.clear();

            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (node.isSelected && !node.isDirectory && !node.isLevelUp) {
                    this.downloadDestinationFilesStack.push(new File(directoryUri.getPath(), node.name ));
                }
            }

            if (!downloadDestinationFilesStack.isEmpty()) {
                File nextDownloadableFile = this.downloadDestinationFilesStack.pop();
                this.transferDialog = new ProgressDialog(this);
                this.transferDialog.setIndeterminate(false);
                this.transferDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                this.transferDialog.setCancelable(false);
                this.transferDialog.setTitle(getString(R.string.dialog_downloading));
                this.transferDialog.setMessage(nextDownloadableFile.getName());
                this.transferDialog.show();
                startFileDownload(nextDownloadableFile);
            }
        } else if (requestCode == FILE_UPLOAD_CODE && resultCode == Activity.RESULT_OK) {
            Uri fileUri = data.getData();
            this.uploadFile = new File(fileUri.getPath());

            if (uploadFile.exists() && uploadFile.isFile()) {
                Log.i(TAG, "Selected file to upload: " + uploadFile.getPath());

                String fileName;
                if ("".equals(currentPath)) {
                    fileName = uploadFile.getName();
                } else {
                    fileName = this.currentPath + "/" + uploadFile.getName();
                }
                Log.i(TAG, "Upload file location:" + fileName);

                if (this.uartGattCallback.startUpload(this.uploadFile)) {
                    this.transferDialog = new ProgressDialog(this);
                    this.transferDialog.setIndeterminate(false);
                    this.transferDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                    this.transferDialog.setCancelable(false);
                    this.transferDialog.setTitle(getString(R.string.dialog_upload));
                    this.transferDialog.setMessage(this.uploadFile.getName());
                    this.transferDialog.setMax((int)this.uploadFile.length());
                    this.transferDialog.show();

                    UartGattAsyncTask uartGattAsyncTask = new UartGattAsyncTask(UartMessageType.PUT_FILE, this.uartGattCallback, this.bluetoothGatt);
                    uartGattAsyncTask.execute("@PUTF:" + fileName + "%" + uploadFile.length() + "#");
                } else {
                    this.uploadFile = null;
                    Log.e(TAG, "File upload can not be started!");
                }
            }
        }
    }

    private void startFileDownload(File file) {
        String fileName;
        if ("".equals(currentPath)) {
            fileName = file.getName();
        } else {
            fileName = this.currentPath + "/" + file.getName();
        }
        Log.i(TAG, "Start to download: " + fileName);

        if (this.uartGattCallback.startDownload(file)) {
            UartGattAsyncTask uartGattAsyncTask = new UartGattAsyncTask(UartMessageType.GET_FILE, this.uartGattCallback, this.bluetoothGatt);
            uartGattAsyncTask.execute("@GETF:" + fileName + "#");
        } else {
            Log.e(TAG, fileName + " download was failed to start!");
        }
    }

    private void startFileDelete(String fileName) {
        UartGattAsyncTask uartGattAsyncTask = new UartGattAsyncTask(UartMessageType.DELETE_FILE, this.uartGattCallback, this.bluetoothGatt);
        uartGattAsyncTask.execute("@DELF:" + fileName + "#");
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        FileSystemNode selectedNode = (FileSystemNode) this.nodesListAdapter.getItem(position);
        Log.i(TAG, "Selected node: " + selectedNode.name);

        if (selectedNode.isLevelUp) {
            int separatorIndex = this.currentPath.lastIndexOf('/');
            if (separatorIndex > 0) {
                this.currentPath = this.sendListDirectory(this.currentPath.substring(0, separatorIndex));
            } else {
                this.currentPath = this.sendListDirectory(""); //ROOT
            }
        } else if (selectedNode.isDirectory) {
            this.currentPath = this.sendListDirectory(this.currentPath, selectedNode.name);
        } else {
            String fileName;
            if ("".equals(currentPath)) {
                fileName = selectedNode.name;
            } else {
                fileName = this.currentPath + "/" + selectedNode.name;
            }
            UartGattAsyncTask uartGattAsyncTask = new UartGattAsyncTask(UartMessageType.INFO, this.uartGattCallback, this.bluetoothGatt);
            uartGattAsyncTask.execute("@INFO:" + fileName + "#");
            this.loadingDialog.setMessage(getString(R.string.dialog_loading));
            this.loadingDialog.show();
            Log.i(TAG, "File info message was sent: " + fileName);
        }
        Log.i(TAG, "The current path is: " + this.currentPath);
    }

    private String sendListDirectory(String path) {
        return this.sendListDirectory(path, null);
    }

    private String sendListDirectory(String path, String directoryName) {

        this.loadingDialog.setMessage(getString(R.string.dialog_loading));
        this.loadingDialog.show();

        String extendedPath = path;
        if (directoryName != null) {
            if ("".equals(path)) {
                extendedPath = directoryName;
            } else {
                extendedPath = path + "/" + directoryName;
            }
        }

        UartGattAsyncTask uartGattAsyncTask = new UartGattAsyncTask(UartMessageType.LIST, this.uartGattCallback, this.bluetoothGatt);
        if ("".equals(extendedPath)) {
            uartGattAsyncTask.execute("@LIST#"); //ROOT
        } else {
            uartGattAsyncTask.execute("@LIST:" + extendedPath + "#");
        }

        return extendedPath;
    }

    private class BrowseMessageHandler extends Handler {

        BrowseMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == UartGattCallback.MESSAGE_BROWSE_COMPLETE) {
                nodesListAdapter.addNodes(this.parseBrowseReplyMessage((String)msg.obj));
                nodesListAdapter.sort();
                nodesListAdapter.notifyDataSetChanged();
                loadingDialog.dismiss();
            } else if (msg.what == UartGattCallback.FILE_DOWNLOAD_STARTED) {
                transferDialog.setMax((Integer)msg.obj);
            } else if (msg.what == UartGattCallback.FILE_DOWNLOAD_IN_PROGRESS) {
                transferDialog.setProgress((Integer)msg.obj);
            } else if (msg.what == UartGattCallback.FILE_DOWNLOAD_FINISHED) {
                if (!downloadDestinationFilesStack.isEmpty()) {
                    //Wait some time to allow the channel to settle
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                    }
                    //Download the next file
                    transferDialog.setProgress(0);
                    File nextDownloadableFile = downloadDestinationFilesStack.pop();
                    transferDialog.setMessage(nextDownloadableFile.getName());
                    transferDialog.setMax((int)nextDownloadableFile.length());
                    startFileDownload(nextDownloadableFile);
                } else {
                    unselectAll();
                    //All files are downloaded - dismiss the loading dialog
                    transferDialog.dismiss();
                    transferDialog.setProgress(0);
                }
            } else if (msg.what == UartGattCallback.FILE_DELETE_FINISHED) {
                if (!deleteDestinationFilesStack.isEmpty()) {
                    //Wait some time to allow the channel to settle
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                    //Delete the next file
                    startFileDelete(deleteDestinationFilesStack.pop());
                } else {
                    loadingDialog.dismiss();
                    sendListDirectory(currentPath); //Update the directory listing
                }
            } else if (msg.what == UartGattCallback.FILE_INFO_READY) {
                String fileName = msg.getData().getString("NAME");
                Integer fileSize = msg.getData().getInt("SIZE");
                String creationDate = msg.getData().getString("CREATION_DATE");
                String modificationDate = msg.getData().getString("MODIFICATION_DATE");

                Dialog infoDialog = new Dialog(BrowseActivity.this);
                infoDialog.setTitle(fileName);
                infoDialog.setContentView(R.layout.info_dialog);

                TextView fileNameTextView = (TextView) infoDialog.findViewById(R.id.file_name);
                TextView fileSizeTextView = (TextView) infoDialog.findViewById(R.id.file_size);
                TextView fileCreationDateTextView = (TextView) infoDialog.findViewById(R.id.created_date);
                TextView fileModificationDateTextView = (TextView) infoDialog.findViewById(R.id.modified_date);

                fileNameTextView.setText(fileName);
                fileSizeTextView.setText(new DecimalFormat("#,###,###").format(fileSize));
                fileCreationDateTextView.setText(creationDate);
                fileModificationDateTextView.setText(modificationDate);

                loadingDialog.dismiss();
                infoDialog.show();
            } else if (msg.what == UartGattCallback.FILE_UPLOAD_STARTED) {
                Log.i(TAG, "Start file upload for: " + ((File)msg.obj).getName());
                //File upload will be done in a separate thread
                FileUploadAsyncTask fileUploadAsyncTask = new FileUploadAsyncTask(UartMessageType.UPLOAD, uartGattCallback, bluetoothGatt);
                fileUploadAsyncTask.setProgressDialog(transferDialog);
                fileUploadAsyncTask.execute((File)msg.obj);
            } else if (msg.what == UartGattCallback.FILE_UPLOAD_ERROR) {
                transferDialog.dismiss();
            } else if (msg.what == UartGattCallback.FILE_UPLOAD_FINISHED) {
                Log.i(TAG, "File upload was finished.");
                this.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        transferDialog.dismiss();
                        sendListDirectory(currentPath);
                    }
                }, 1000);
            }
        }

        private List<FileSystemNode> parseBrowseReplyMessage(String replyMessage) {
            List<FileSystemNode> nodes = new ArrayList<>();
            String[] fileSystemNodesArray = replyMessage.split(",");
            for (String part : fileSystemNodesArray) {
                FileSystemNode node = new FileSystemNode();
                if (part.equalsIgnoreCase("../")) {
                    node.isLevelUp = true;
                    node.name = "..";
                } else if (part.contains("/")) {
                    node.isDirectory = true;
                    node.name = part.substring(0, part.indexOf('/'));
                } else {
                    node.name = part;
                }
                nodes.add(node);
            }
            return nodes;
        }
    }

    private class NodesListAdapter extends BaseAdapter {
        private ArrayList<FileSystemNode> fileSystemNodes = new ArrayList<>();

        public void addNodes(List<FileSystemNode> nodes) {
            this.clear();
            this.fileSystemNodes.addAll(nodes);
        }

        public List<FileSystemNode> nodes() {
            return this.fileSystemNodes;
        }

        public void clear() {
            fileSystemNodes.clear();
        }

        public void sort() {
            //Directories come first - otherwise alphabetical sort
            Collections.sort(this.fileSystemNodes, new Comparator<FileSystemNode>() {
                @Override
                public int compare(FileSystemNode o1, FileSystemNode o2) {
                    if (o1.name.contains("..")) {
                        return -1;
                    }
                    if (o2.name.contains("..")) {
                        return 1;
                    }

                    if (o1.isDirectory && o2.isDirectory) {
                        return o1.name.compareTo(o2.name);
                    } else if (o1.isDirectory && !o2.isDirectory) {
                        return -1;
                    } else if (!o1.isDirectory && o2.isDirectory) {
                        return 1;
                    } else {
                        return o1.name.compareTo(o2.name);
                    }
                }
            });
        }

        @Override
        public int getCount() {
            return fileSystemNodes.size();
        }

        @Override
        public Object getItem(int i) {
            return fileSystemNodes.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup parent) {
            BrowseActivity.ViewHolder viewHolder;

            if (view == null) {
                LayoutInflater inflater = BrowseActivity.this.getLayoutInflater();
                view = inflater.inflate(R.layout.listitem_file, parent, false);
                viewHolder = new BrowseActivity.ViewHolder();
                viewHolder.nodeNameTextView = (TextView) view.findViewById(R.id.nodeNameTextView);
                viewHolder.nodeTypeImageView = (ImageView) view.findViewById(R.id.nodeTypeImageView);
                viewHolder.nodeSelectionCheckBox = (CheckBox) view.findViewById(R.id.nodeSelectionCheckBox);
                view.setTag(viewHolder);

                viewHolder.nodeSelectionCheckBox.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CheckBox checkBox = (CheckBox)v;
                        FileSystemNode node = (FileSystemNode)checkBox.getTag();
                        node.isSelected = checkBox.isChecked();
                        Log.i(TAG, "Selection change for: " + node.name + " Status: " + node.isSelected);
                    }
                });
            } else {
                viewHolder = (BrowseActivity.ViewHolder) view.getTag();
            }

            FileSystemNode fileSystemNode = fileSystemNodes.get(i);

            viewHolder.nodeNameTextView.setText(fileSystemNode.name);
            if (fileSystemNode.isLevelUp) {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.folder_open);
                viewHolder.nodeSelectionCheckBox.setVisibility(View.INVISIBLE);
            } else if (fileSystemNode.isDirectory) {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.folder);
                viewHolder.nodeSelectionCheckBox.setVisibility(View.INVISIBLE);
            } else {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.file);
                if (fileSystemNode.isSelected) {
                    viewHolder.nodeSelectionCheckBox.setChecked(true);
                } else {
                    viewHolder.nodeSelectionCheckBox.setChecked(false);
                }
                viewHolder.nodeSelectionCheckBox.setVisibility(View.VISIBLE);
            }

            viewHolder.nodeSelectionCheckBox.setTag(fileSystemNode);

            return view;
        }
    }

    private class FileSystemNode {
        String name;
        boolean isLevelUp = false;
        boolean isDirectory = false;
        boolean isSelected = false;
    }

    static class ViewHolder {
        TextView nodeNameTextView;
        ImageView nodeTypeImageView;
        CheckBox nodeSelectionCheckBox;
    }

}
