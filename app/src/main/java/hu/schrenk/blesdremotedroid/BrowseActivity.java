package hu.schrenk.blesdremotedroid;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.os.AsyncTask;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import hu.schrenk.blesdremotedroid.ble.UartGattAsyncTask;
import hu.schrenk.blesdremotedroid.ble.UartGattCallback;
import hu.schrenk.blesdremotedroid.ble.UartMessageType;

public class BrowseActivity extends AppCompatActivity implements AdapterView.OnItemClickListener{

    private static final String TAG = "BrowseActivity";

    private BluetoothDevice bluetoothDevice;
    private BluetoothGatt bluetoothGatt;
    private UartGattCallback uartGattCallback;
    private Handler browseMessageHandler;

    private ListView nodesListView;
    private NodesListAdapter nodesListAdapter;
    private ProgressDialog loadingDialog;

    private String currentPath = ""; //ROOT

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        this.getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        this.loadingDialog = new ProgressDialog(this);
        this.loadingDialog.setIndeterminate(true);
        this.loadingDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        this.loadingDialog.setMessage(getString(R.string.dialog_loading));

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
        this.currentPath = this.sendListDirectory(""); //ROOT
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.nodesListAdapter.clear();

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
            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (node.isSelected && !node.isDirectory && !node.isLevelUp) {
                    //TODO Download the selected file from SD card
                    Log.i(TAG, "Download " + this.currentPath + "/" + node.name);
                }
            }
        } else if (id == R.id.action_select_all) {
            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (!node.isDirectory && !node.isLevelUp) {
                    node.isSelected = true;
                }
            }
            this.nodesListAdapter.notifyDataSetChanged();
            return true;
        } else if (id == R.id.action_unselect_all) {
            for (FileSystemNode node : this.nodesListAdapter.nodes()) {
                if (!node.isDirectory && !node.isLevelUp) {
                    node.isSelected = false;
                }
            }
            this.nodesListAdapter.notifyDataSetChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        }
        Log.i(TAG, "The current path is: " + this.currentPath);
    }

    private String sendListDirectory(String path) {
        return this.sendListDirectory(path, null);
    }

    private String sendListDirectory(String path, String directoryName) {

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
