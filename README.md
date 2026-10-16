# ELRS Telemetri (Android)

ExpressLRS TX Backpack'in **Telemetry = WiFi** modunda UDP ile yayınladığı CRSF telemetrisini
telefonda/tablette gösterir. MAVLink gerekmez.

Gösterilenler: uçuş modu, yapay ufuk, LQ, RSSI 1/2, SNR, TX gücü, downlink; voltaj, hücre sayısı
ve hücre voltajı, akım, mAh, kalan %; GPS (uydu, hız, irtifa, koordinat), ev uzaklığı ve yönü,
baro irtifa, vario. 3 sn'den eski veriler griye döner; LQ ve batarya için renkli uyarı vardır.

## Kullanım

1. Kumandada ELRS Lua → **Backpack → Telemetry → WiFi**. (Elle "Enable Backpack WiFi" seçmeyin; o güncelleme modudur.)
2. Kumandada Link Mode **Normal** (CRSF) olmalı.
3. Tableti backpack ağına bağlayın: `ExpressLRS TX Backpack XXXXXX`, şifre `expresslrs`.
   Android "İnternet yok" derse **bağlı kal** seçin; gerekirse mobil veriyi kapatın.
   (Backpack'e ev ağı/hotspot tanımladıysanız tableti o ağa bağlayın.)
4. Uygulamayı açın. Varsayılan port UDP **14550** (Ayarlar bölümünden değişir).
5. Veri gelmiyorsa **Ham veri** düğmesiyle gelen paketlere bakın. Üst satırdaki uyarılar
   paket gelip gelmediğini, MAVLink mi CRSF mi geldiğini söyler.

Ev noktası, uydu sayısı 6 ve üzeri olan ilk GPS verisinde otomatik alınır; **Evi sıfırla** ile yenilenir.

## APK derleme

### A) GitHub Actions (bilgisayarda bir şey kurmadan)
1. Bu klasörü yeni bir GitHub deposuna yükleyin.
2. **Actions** sekmesinde "APK derle" iş akışı çalışır (ya da elle *Run workflow*).
3. Bitince sayfanın altındaki **ELRS-Telemetri-apk** dosyasını indirin, zip'ten `app-debug.apk` çıkar.

### B) Android Studio
Klasörü *Open* ile açın → senkronizasyon bitince ▶ Run (telefon USB'den bağlı ve geliştirici modunda).

### Kurulum
APK'yı telefona atıp açın; "bilinmeyen kaynaklardan yüklemeye izin ver" uyarısını onaylayın.

## Bilinen sınırlar / doğrulanmamış noktalar

- WiFi modunda CRSF'in UDP broadcast ile 14550'ye gönderildiği bilgisi üçüncü taraf bir kaynaktan
  (SquirrelCast kılavuzu) gelir; Backpack 1.5.7+ gerekir. Bazı ESP32-C3 hedeflerinde 1.5.7'de
  WiFi telemetrisi başlamayabilir (geçici çözüm: Link Mode'u MAVLink yapıp tekrar Normal'e almak).
- UDP paketinin iç yapısı doğrulanmadığından uygulama paketi tarar ve yalnızca CRC'si ile
  uzunluğu tutan CRSF çerçevelerini kabul eder.
- Lua script'lerinin hesapladığı türetilmiş değerlerin (alarm, özel hesaplar) hepsi burada yoktur.
- Uygulama yalnızca ekrandayken dinler (arka plan servisi yok); ekran açık tutulur.

## Kod

- `CrsfParser.java` — çerçeve tarama, CRC8 (0xD5), tip çözme (Android'den bağımsız, test edilebilir)
- `TelemetryState.java` — son değerler, ev noktası, uzaklık/yön
- `MainActivity.java` — UDP alıcı, arayüz
- `HorizonView.java` — yapay ufuk
- `app/src/test/.../CrsfParserTest.java` — ayrıştırıcı testleri (`./gradlew testDebugUnitTest`)
