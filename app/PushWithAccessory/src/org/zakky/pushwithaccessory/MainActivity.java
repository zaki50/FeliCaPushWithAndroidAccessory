
package org.zakky.pushwithaccessory;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class MainActivity extends Activity implements Runnable {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String ACTION_USB_PERMISSION = "com.google.android.DemoKit.action.USB_PERMISSION";

    private UsbManager mUsbManager;
    private PendingIntent mPermissionIntent;
    private boolean mPermissionRequestPending;

    private UsbAccessory mAccessory;
    private ParcelFileDescriptor mFileDescriptor;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbAccessory accessory = UsbManager.getAccessory(intent);
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openAccessory(accessory);
                    } else {
                        Log.d(TAG, "permission denied for accessory "
                                + accessory);
                    }
                    mPermissionRequestPending = false;
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory accessory = UsbManager.getAccessory(intent);
                if (accessory != null && accessory.equals(mAccessory)) {
                    closeAccessory();
                }
            }
        }
    };

    private void openAccessory(UsbAccessory accessory) {
        mFileDescriptor = mUsbManager.openAccessory(accessory);
        if (mFileDescriptor != null) {
            mAccessory = accessory;
            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);
            Thread thread = new Thread(null, this, "RC620S");
            thread.start();
            Log.d(TAG, "accessory opened");
            enableControls(true);
        } else {
            Log.d(TAG, "accessory open fail");
        }
    }

    private void closeAccessory() {
        enableControls(false);

        try {
            if (mFileDescriptor != null) {
                mFileDescriptor.close();
            }
        } catch (IOException e) {
        } finally {
            mFileDescriptor = null;
            mAccessory = null;
        }
    }

    private void enableControls(boolean enable) {
        if (enable) {
            showControls();
        } else {
            hideControls();
        }
    }

    private void hideControls() {
        setContentView(R.layout.no_device);
    }

    private void showControls() {
        setContentView(R.layout.main);
    }

    private void sendCommand(String url) {
        if (url.isEmpty()) {
            return;
        }
        
        final byte[] pushCommand = buildPushCommand(url);
        if (pushCommand == null) {
            return;
        }
        final byte[] data = new byte[pushCommand.length + 1];
        data[0] = (byte) pushCommand.length;
        System.arraycopy(pushCommand, 0, data, 1, pushCommand.length);

        if (mOutputStream != null) {
            try {
                mOutputStream.write(data);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
        }
    }

    private static final Charset URL_CHARSET = Charset.forName("iso8859-1");

    private static final Charset STARTUP_PARAM_CHARSET = Charset.forName("iso8859-1");

    private byte[] buildPushCommand(String url) {
        final String browserStartupParam = "";
        final byte TYPE = (byte) 2;

        final byte[] urlByte = (url == null) ? new byte[0] : url.getBytes(URL_CHARSET);
        final byte[] browserStartupParamByte = (browserStartupParam == null) ? new byte[0]
                : browserStartupParam.getBytes(STARTUP_PARAM_CHARSET);

        final int capacity = urlByte.length + browserStartupParamByte.length //
                + 5;// type(1byte) + paramSize(2bytes) + urlLength(2bytes)

        final ByteBuffer buffer = ByteBuffer.allocate(capacity);

        // 個別部ヘッダ

        // 起動制御情報
        buffer.put(TYPE);
        // 個別部パラメータサイズ
        int paramSize = urlByte.length + browserStartupParamByte.length + 2; // urlLength(2bytes)
        putShortAsLittleEndian(paramSize, buffer);

        // 個別部パラメータ

        // URLサイズ
        putShortAsLittleEndian(urlByte.length, buffer);
        // URL
        buffer.put(urlByte);
        // (ブラウザスタートアップパラメータ)
        buffer.put(browserStartupParamByte);

        return packSegments(new byte[][] {buffer.array()});
    }
    private static byte[] packSegments(byte[]... segments) {
        int bytes = 3; // 個別部数(1byte) + チェックサム(2bytes)
        for (int i = 0; i < segments.length; ++i) {
            bytes += segments[i].length;
        }

        final ByteBuffer buffer = ByteBuffer.allocate(bytes);

        // 個別部数
        buffer.put((byte) segments.length);

        // 個別部
        for (int i = 0; i < segments.length; ++i) {
            buffer.put(segments[i]);
        }

        // チェックサム
        int sum = segments.length;
        for (int i = 0; i < segments.length; ++i) {
            byte[] e = segments[i];
            for (int j = 0; j < e.length; ++j) {
                sum += e[j];
            }
        }
        final int checksum = -sum & 0xffff;
        putShortAsBigEndian(checksum, buffer);

        return buffer.array();
    }
    private static void putShortAsLittleEndian(int value, ByteBuffer buffer) {
        buffer.put((byte) ((value >> 0) & 0xff));
        buffer.put((byte) ((value >> 8) & 0xff));
    }
    private static void putShortAsBigEndian(int value, ByteBuffer buffer) {
        buffer.put((byte) ((value >> 8) & 0xff));
        buffer.put((byte) ((value >> 0) & 0xff));
    }

    private EditText mUrlField;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUsbManager = UsbManager.getInstance(this);
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        registerReceiver(mUsbReceiver, filter);

        if (getLastNonConfigurationInstance() != null) {
            mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
            openAccessory(mAccessory);
        }

        setContentView(R.layout.main);

        mUrlField = (EditText) findViewById(R.id.url);

       enableControls(false);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        if (mAccessory != null) {
            return mAccessory;
        } else {
            return super.onRetainNonConfigurationInstance();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        Intent intent = getIntent();
        if (mInputStream != null && mOutputStream != null) {
            return;
        }

        UsbAccessory[] accessories = mUsbManager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {
            if (mUsbManager.hasPermission(accessory)) {
                openAccessory(accessory);
            } else {
                synchronized (mUsbReceiver) {
                    if (!mPermissionRequestPending) {
                        mUsbManager.requestPermission(accessory,
                                mPermissionIntent);
                        mPermissionRequestPending = true;
                    }
                }
            }
        } else {
            Log.d(TAG, "mAccessory is null");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        closeAccessory();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mUsbReceiver);
        super.onDestroy();
    }

    public void onClickSendButton(View v) {
        final String url = mUrlField.getText().toString();

        // Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.empty_url, Toast.LENGTH_LONG).show();
            return;
        }
        sendCommand(url);
    }

    public void onClickClearButton(View v) {
        mUrlField.setText("");
    }

    public void run() {
    }
}
