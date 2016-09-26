package hu.schrenk.blesdremotedroid;

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
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import hu.schrenk.blesdremotedroid.ble.UartGattCallback;

public class BrowseActivity extends AppCompatActivity implements AdapterView.OnItemClickListener{

    private static final String TAG = "BrowseActivity";

    private BluetoothDevice bluetoothDevice;
    private BluetoothGatt bluetoothGatt;
    private UartGattCallback uartGattCallback;
    private Handler browseMessageHandler;

    private ListView nodesListView;
    private NodesListAdapter nodesListAdapter;

    private String currentPath = "/"; //Root

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        this.getSupportActionBar().setDisplayHomeAsUpEnabled(true);

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

        Log.i(TAG, "onCreate() - DONE");
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
        new SdCardBrowseAsyncTask().execute("@LIST#");
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.nodesListAdapter.clear();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        FileSystemNode selectedNode = (FileSystemNode) this.nodesListAdapter.getItem(position);
        Log.i(TAG, "Selected node: " + selectedNode.name + " Path: " + selectedNode.path);

        if (selectedNode.isLevelUp) {
            //TODO Move one level up
        } else if (selectedNode.isDirectory) {
            //TODO Open the directory
        }
    }

    private class NodesListAdapter extends BaseAdapter {
        private ArrayList<FileSystemNode> fileSystemNodes = new ArrayList<>();

        public void addNodes(List<FileSystemNode> nodes) {
            this.clear();
            this.fileSystemNodes.addAll(nodes);
        }

        public void clear() {
            fileSystemNodes.clear();
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
                view.setTag(viewHolder);
            } else {
                viewHolder = (BrowseActivity.ViewHolder) view.getTag();
            }

            FileSystemNode fileSystemNode = fileSystemNodes.get(i);

            viewHolder.nodeNameTextView.setText(fileSystemNode.name);
            if (fileSystemNode.isLevelUp) {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.folder_open);
            } else if (fileSystemNode.isDirectory) {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.folder);
            } else {
                viewHolder.nodeTypeImageView.setImageResource(R.drawable.file);
            }
            return view;
        }
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
                nodesListAdapter.notifyDataSetChanged();
            }
        }

        private List<FileSystemNode> parseBrowseReplyMessage(String replyMessage) {
            List<FileSystemNode> nodes = new ArrayList<>();
            String[] fileSystemNodesArray = replyMessage.split(",");
            for (String part : fileSystemNodesArray) {
                FileSystemNode node = new FileSystemNode();
                if (part.equalsIgnoreCase("../")) {
                    node.isLevelUp = true;
                    node.path = currentPath.substring(0, currentPath.indexOf('/'));
                    node.name = "..";
                } else if (part.contains("/")) {
                    node.isDirectory = true;
                    node.path = currentPath;
                    node.name = part.substring(0, part.indexOf('/'));
                } else {
                    node.path = currentPath;
                    node.name = part;
                }
                nodes.add(node);
            }
            return nodes;
        }
    }

    private class FileSystemNode {
        String path;
        String name;
        boolean isLevelUp = false;
        boolean isDirectory = false;
    }

    private class SdCardBrowseAsyncTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {

            Log.i(TAG, "Waiting for connection...");

            if (!BrowseActivity.this.uartGattCallback.isConnected()) {
                // Wait until the UART connection gets established
                while (!BrowseActivity.this.uartGattCallback.isConnected()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Log.e(TAG, e.getLocalizedMessage(), e);
                    }
                }
            }

            Log.i(TAG, "Sending the message...");

            //Send the root list message
            BrowseActivity.this.uartGattCallback.send(BrowseActivity.this.bluetoothGatt, params[0]);
            return null;
        }

        @Override
        protected void onPostExecute(String response) {
            super.onPostExecute(response);

            //Refresh the UI based on the new directory structure
        }
    }

    static class ViewHolder {
        TextView nodeNameTextView;
        ImageView nodeTypeImageView;
    }

}
