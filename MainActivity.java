package com.example.myapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    private static final UUID UUID_SERIAL_PORT_PROFILE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private Button connectButton;
    private TextView receivedText;
    private EditText commandText;
    private Button sendButton;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice arduinoDevice;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private LocationManager locationManager;
    private LocationListener locationListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectButton = findViewById(R.id.connectButton);
        receivedText = findViewById(R.id.receivedText);
        commandText = findViewById(R.id.commandText);
        sendButton = findViewById(R.id.sendButton);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
        }

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!bluetoothAdapter.isEnabled()) {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                } else {
                    connectToArduino();
                }
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCommandToArduino(commandText.getText().toString());
            }
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                sendLocationToArduino(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
    }

    private void connectToArduino() {
        arduinoDevice = bluetoothAdapter.getRemoteDevice("00:21:07:00:0A:34");
        try {
            bluetoothSocket = arduinoDevice.createRfcommSocketToServiceRecord(UUID_SERIAL_PORT_PROFILE);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();
            inputStream = bluetoothSocket.getInputStream();
            Toast.makeText(this, "Connected to Arduino", Toast.LENGTH_SHORT).show();

            // Request location updates when connected to Arduino
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION);
            }

            // Start a thread to listen for incoming messages from Arduino
            startListeningForData();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to connect to Arduino", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListeningForData() {
        final byte delimiter = 10; // Newline ASCII value
        Thread dataListenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] readBuffer = new byte[1024];
                int readBufferPosition = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        int bytesAvailable = inputStream.available();
                        if (bytesAvailable > 0) {
                            byte[] packetBytes = new byte[bytesAvailable];
                            inputStream.read(packetBytes);
                            for (int i = 0; i < bytesAvailable; i++) {
                                byte b = packetBytes[i];
                                if (b == delimiter) {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    // Process received data on the UI thread
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            receivedText.append(data + "\n");

                                            // Check if the specific response is received
                                            if (data.equals("GET_LOCATION")) {
                                                
                                                // Request location data
                                                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                                                        == PackageManager.PERMISSION_GRANTED) {
                                                    Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                                    if (lastKnownLocation != null) {
                                                        sendLocationToArduino(lastKnownLocation);
                                                    }
                                                }
                                            }
                                        }
                                    });
                                } else {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        });

        dataListenerThread.start();
    }


    private void sendCommandToArduino(String command) {
        if (outputStream != null) {
            try {
                outputStream.write(command.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to send command to Arduino", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendLocationToArduino(Location location) {
        if (outputStream != null) {
            String locationData = location.getLatitude() + "," + location.getLongitude() + "\n";
            Toast.makeText(this, locationData , Toast.LENGTH_SHORT).show();
            try {
                outputStream.write(locationData.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to send location to Arduino", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }
}
