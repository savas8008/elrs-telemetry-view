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
3. Bitince deponun **Releases** bölümünden (sağ tarafta) en son sürümdeki `.apk` dosyasını indirin.

### B) Android Studio
Klasörü *Open* ile açın → senkronizasyon bitince ▶ Run (telefon USB'den bağlı ve geliştirici modunda).

### Kurulum
APK'yı telefona atıp açın; "bilinmeyen kaynaklardan yüklemeye izin ver" uyarısını onaylayın.

## Gereksinimler (kaynak koddan doğrulandı)

- **ELRS TX firmware 3.5.0+** (Lua'daki `Off;ESPNOW;WiFi` seçeneği ve CRSF telemetrisini backpack'e gönderme bu sürümle geldi).
- **TX Backpack firmware 1.5.8+**. 1.5.7 ve öncesinde "Telemetry = WiFi" seçilince WiFi açılmıyor;
  düzeltme Backpack deposundaki `2c863af` "Fix Normal TLM mode wifi" commit'i, ilk sürüm 1.5.8.
- Link Mode **Normal** olmalı (MAVLink modunda CRSF telemetrisi backpack'e gönderilmez).
- WiFi seçeneği değiştirildiğinde backpack ayarı kaydedip yeniden başlar; WiFi birkaç saniye sonra açılır.
- Ağ: `ExpressLRS TX Backpack XXXXXX` (XXXXXX = bind phrase UID'sinin son 3 baytı), şifre `expresslrs`,
  backpack IP `10.0.0.1`; paketler AP alt ağına broadcast, varsayılan port 14550.

## Bilinen sınırlar

- Lua script'lerinin hesapladığı türetilmiş değerlerin (alarm, özel hesaplar) hepsi burada yoktur.
- Uygulama yalnızca ekrandayken dinler (arka plan servisi yok); ekran açık tutulur.

## Kod

- `CrsfParser.java` — çerçeve tarama, CRC8 (0xD5), tip çözme (Android'den bağımsız, test edilebilir)
- `TelemetryState.java` — son değerler, ev noktası, uzaklık/yön
- `MainActivity.java` — UDP alıcı, arayüz
- `HorizonView.java` — yapay ufuk
- `app/src/test/.../CrsfParserTest.java` — ayrıştırıcı testleri (`./gradlew testDebugUnitTest`)
