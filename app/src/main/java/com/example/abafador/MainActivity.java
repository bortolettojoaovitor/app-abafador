package com.example.abafador;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.UUID;

public class MainActivity extends AppCompatActivity
{

    private TextView textoStatusConexao, textoBateria, textoRuidoAtual, textoValorVolume, textoValorCorteDB;
    private SeekBar barraControleVolume, barraControleCorteDB;
    private Button botaoModoFoco, botaoModoConforto, botaoModoConversa;

    private static final String ESP32_MAC_ADDRESS = "98:88:E0:14:CD:29";
    private static final UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID CHAR_RX_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic caracteristicaTX;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textoStatusConexao = findViewById(R.id.textoStatusConexao);
        textoBateria = findViewById(R.id.textoBateria);
        textoRuidoAtual = findViewById(R.id.textoRuidoAtual);
        textoValorVolume = findViewById(R.id.textoValorVolume);
        textoValorCorteDB = findViewById(R.id.textoValorCorteDB);

        barraControleVolume = findViewById(R.id.barraControleVolume);
        barraControleCorteDB = findViewById(R.id.barraControleCorteDB);

        botaoModoFoco = findViewById(R.id.botaoModoFoco);
        botaoModoConforto = findViewById(R.id.botaoModoConforto);
        botaoModoConversa = findViewById(R.id.botaoModoConversa);

        textoRuidoAtual.setText("-- dB");

        botaoModoFoco.setOnClickListener(v -> enviarComandoBluetooth("M:1"));
        botaoModoConforto.setOnClickListener(v -> enviarComandoBluetooth("M:2"));
        botaoModoConversa.setOnClickListener(v -> enviarComandoBluetooth("M:3"));

        barraControleVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progresso, boolean fromUser)
            {
                textoValorVolume.setText(progresso + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                enviarComandoBluetooth("V:" + seekBar.getProgress());
            }
        });

        barraControleCorteDB.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progresso, boolean fromUser)
            {
                textoValorCorteDB.setText(progresso + " dB");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                enviarComandoBluetooth("C:" + seekBar.getProgress());
            }
        });

        //verificacao de seguranca
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                //solicita permissao
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 1);
            }
            else
            {
                iniciarBluetooth();
            }
        }
        else
        {
            iniciarBluetooth();
        }
    }

    //funcao que recebe resposta da permissao
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        {
            iniciarBluetooth();
        }
        else
        {
            textoStatusConexao.setText("Permissão Negada");
            textoStatusConexao.setTextColor(0xFFE53935);
        }
    }

    @SuppressLint("MissingPermission")
    private void iniciarBluetooth()
    {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) return;

        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled())
        {
            textoStatusConexao.setText("Procurando...");
            textoStatusConexao.setTextColor(0xFFE67E22);

            try
            {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(ESP32_MAC_ADDRESS);
                bluetoothGatt = device.connectGatt(this, false, gattCallback);
            }
            catch (IllegalArgumentException e)
            {
                Log.e("AppAbafador", "MAC Address inválido para teste.");
            }
        }
        else
        {
            textoStatusConexao.setText("Bluetooth Desligado");
            textoStatusConexao.setTextColor(0xFFE53935);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback()
    {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            if (newState == BluetoothProfile.STATE_CONNECTED)
            {
                runOnUiThread(() ->
                {
                    textoStatusConexao.setText("● Conectado");
                    textoStatusConexao.setTextColor(0xFF43A047);
                });
                gatt.discoverServices();
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                runOnUiThread(() ->
                {
                    textoStatusConexao.setText("● Desconectado");
                    textoStatusConexao.setTextColor(0xFFE53935);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null)
                {
                    caracteristicaTX = service.getCharacteristic(CHAR_RX_UUID);
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void enviarComandoBluetooth(String comando)
    {
        Log.d("AppAbafador", "Tentando enviar comando: " + comando);
        if (bluetoothGatt != null && caracteristicaTX != null)
        {
            caracteristicaTX.setValue(comando.getBytes());
            bluetoothGatt.writeCharacteristic(caracteristicaTX);
            Log.d("AppAbafador", "Comando enviado!");
        }
        else
        {
            Log.e("AppAbafador", "Ignorado. Sem conexão BLE.");
        }
    }
}