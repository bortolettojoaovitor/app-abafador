#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <driver/i2s.h>

//config ble
//UUIDs do aplicativo Android
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"


int volumeGeral = 50;       // 0 a 100
int corteDB = 85;           // limite de seg
int modoAtual = 1;          // 1:Foco, 2:Conforto, 3:Conversa

//entrada max441
#define PINO_MIC_ADC 41

//saida pcm5102
#define I2S_DAC_PORT I2S_NUM_0

//pinos i2s
#define I2S_DAC_LRC  4  //pino LRCK do PCM5102
#define I2S_DAC_DIN  5  //pino DIN do PCM5102
#define I2S_DAC_BCLK 6  //pino BCK do PCM5102

//SCK desconectado:
#define I2S_DAC_MCLK I2S_PIN_NO_CHANGE

//memória para os filtros
float y_lp = 0; 
float y_hp = 0; 
float x_prev = 0; 

class MyCallbacks: public BLECharacteristicCallbacks 
{
    void onWrite(BLECharacteristic *pCharacteristic) 
    {
        String rxValue = pCharacteristic->getValue(); //usa string do arduino
        
        if (rxValue.length() > 0) 
        {
            // verifica qual comando chegou
            if (rxValue.startsWith("V:")) 
            {
                volumeGeral = rxValue.substring(2).toInt();
                Serial.printf("Volume: %d%%\n", volumeGeral);
            } 
            else if (rxValue.startsWith("C:")) 
            {
                corteDB = rxValue.substring(2).toInt();
                Serial.printf("Corte: %d dB\n", corteDB);
            }
            else if (rxValue.startsWith("M:")) 
            {
                modoAtual = rxValue.substring(2).toInt();
                Serial.printf("Modo: %d\n", modoAtual);
            }
        }
    }
};

//configuracao de saida
void setupI2S() 
{
    i2s_config_t i2s_dac_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
        .sample_rate = 44100, //taxa de amostragem padrão
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT, //envia para ambos os canais
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 8,
        .dma_buf_len = 64,
        .use_apll = true //gerar um Master Clock limpo
    };
    
    i2s_pin_config_t pin_dac_config = {
        .mck_io_num = I2S_DAC_MCLK, 
        .bck_io_num = I2S_DAC_BCLK, 
        .ws_io_num = I2S_DAC_LRC,   
        .data_out_num = I2S_DAC_DIN,
        .data_in_num = I2S_PIN_NO_CHANGE //nao estamos lendo pelo I2s estamos enviadndo
    };
    
    i2s_driver_install(I2S_DAC_PORT, &i2s_dac_config, 0, NULL);
    i2s_set_pin(I2S_DAC_PORT, &pin_dac_config);
}

void setup() 
{
    Serial.begin(115200);
    delay(3000); //tempo pro usb conectar

    //servidor bluetooth
    BLEDevice::init("abafador_ESP32");
    BLEServer *pServer = BLEDevice::createServer();
    BLEService *pService = pServer->createService(SERVICE_UUID);
    BLECharacteristic *pCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE
    );
    pCharacteristic->setCallbacks(new MyCallbacks());
    pService->start();
    BLEAdvertising *pAdvertising = pServer->getAdvertising();
    pAdvertising->start();
    
    Serial.print("MAC: ");
    Serial.println(BLEDevice::getAddress().toString().c_str());

    //inicia entrada analógica no pino IO6
    pinMode(PINO_MIC_ADC, INPUT);
    analogReadResolution(12); //ADC do ESP32 lê valores de 0 a 4095

    //saida digital dacI2S
    setupI2S();
    Serial.println("hardware pronto, esperando conexão...");
}

void loop() 
{
    // le mic analogico
    int leituraADC = analogRead(PINO_MIC_ADC);
    
    //subtraindo 2048 para centralizar a onda do som
    float sample = (float)(leituraADC - 2048); 
    
    //conversao da escala de 12bits para 16bits
    sample = sample * 16.0;

    //processamento digital do sistema
    float multiplicadorVolume = (float)volumeGeral / 100.0;

    if (modoAtual == 1) 
    {
        //modo foco total: silencio total (depende apenas do abafador físico)
        sample = 0; 
    }
    else if (modoAtual == 2) 
    {
        //modo Conforto: FPB
        float alpha = 0.15; //ajustar caso fique mto abafado ou mto alto
        y_lp = y_lp + alpha * (sample - y_lp);
        sample = y_lp;
    }
    else if (modoAtual == 3) 
    {
        //modo Conversação: FPBANDA
        float alpha_lp = 0.4;  
        y_lp = y_lp + alpha_lp * (sample - y_lp);
        
        float alpha_hp = 0.85;
        y_hp = alpha_hp * (y_hp + y_lp - x_prev);
        x_prev = y_lp;
        
        sample = y_hp;
    }

    //corte de seguranca (Lógica visualmente simplificada)
    float limiarSeguranca = (corteDB / 120.0) * 32767.0; 
    if (abs(sample) > limiarSeguranca) 
    {
        if (sample > 0) 
        {
            sample = limiarSeguranca;
        } 
        else 
        {
            sample = -limiarSeguranca;
        }
    }

    //aplica o final do v0lume
    int16_t amostraFinal = (int16_t)(sample * multiplicadorVolume);

    //envia para o dac e auto falante
    //mandamos o mesmo som para Esquerda e Direita.
    int16_t buffer[2] = {amostraFinal, amostraFinal}; 
    size_t bytesOut;
    i2s_write(I2S_DAC_PORT, &buffer, sizeof(buffer), &bytesOut, portMAX_DELAY);
    
    //estabilizar o ciclo
    delayMicroseconds(22); 
}