# ELRS Telemetry View — Android GCS

ExpressLRS kumandanızın telemetrisini **telefonda veya tablette, harita üzerinde** gösteren
yer kontrol istasyonu (GCS). MAVLink gerekmez: TX Backpack'in **Telemetry = WiFi** modunda
yayınladığı CRSF telemetrisini doğrudan okur.

> [!IMPORTANT]
> **"Telemetry = WiFi" seçince backpack WiFi'ı açılmıyorsa backpack firmware'inizi güncelleyin.**
> Backpack **1.5.7 ve öncesinde** bu mod çalışmıyor; düzeltme **1.5.8**'de geldi
> (Backpack deposu, commit `2c863af` "Fix Normal TLM mode wifi"). En az **1.5.8**, tercihen en
> güncel sürümü (1.5.9) yükleyin. RadioMaster TX15 üzerinde güncellemeden sonra çalıştığı doğrulandı.
>
> Sürümünüzü görmek için: ELRS Lua → **Backpack → Version**.
> Güncellemek için: **ExpressLRS Configurator → Backpack** sekmesi → üreticiniz ve modülünüz
> (ör. *RadioMaster TX15 2.4/900 TX*) → yöntem **EdgeTX Passthrough** (kumanda USB'den bağlı,
> "USB Serial (VCP)" seçili) veya WiFi.

## Özellikler

- **Tam ekran harita**: Uydu (Esri), Sokak (OpenStreetMap) veya Topoğrafik (OpenTopoMap)
- **Uçak simgesi** gidiş yönüne göre döner, **uçuş izi** (rota) çizilir, **ev noktası** ve eve uzanan çizgi
- **Takip modu**: harita uçağı izler; haritayı sürükleyince kapanır, ⌖ ile tekrar açılır
- **Yapay ufuk** ve pusula şeridi
- **Tüm telemetri tek ekranda**: irtifa, vario, hız, ev uzaklığı, eve dönüş yönü, GPS irtifası,
  voltaj, hücre voltajı, akım, harcanan mAh, güç, LQ, RSSI, SNR, TX gücü, downlink, anten, RF modu,
  uydu sayısı, uçuş modu, ARM durumu
- **İrtifa ve voltaj grafikleri** (son 5 dakika)
- **Uçuş kaydı (CSV)**: varsayılan olarak açık; veri gelince otomatik başlar
- **Sesli uyarılar** (Türkçe): düşük batarya, zayıf bağlantı, telemetri kaybı
- **Uçuş özeti**: süre, en yüksek irtifa, en uzak mesafe, en yüksek hız/akım, en düşük hücre voltajı
- Varsayılan **yatay ekran**, tam ekran, ekran kapanmaz

## Hızlı başlangıç

1. **APK'yı kurun**: deponun **Releases** bölümünden en son `ELRS-Telemetri-*.apk` dosyasını indirip açın
   ("bilinmeyen kaynaklardan yükleme" iznini verin). Android 10 veya üstü gerekir.
2. Kumandada **Link Mode = Normal** olsun (MAVLink değil).
3. ELRS Lua → **Backpack → Telemetry → WiFi**. Seçenek zaten WiFi ise önce Off yapıp tekrar WiFi seçin.
   Backpack yeniden başlar; birkaç saniye bekleyin.
4. Telefonu/tableti **`ExpressLRS TX Backpack XXXXXX`** ağına bağlayın, şifre **`expresslrs`**.
   Android "İnternet yok" derse **Bağlı kal**'ı seçin.
5. Uygulamayı açın. Veri birkaç saniye içinde gelmeye başlar.

### Harita için internet

Backpack ağında internet yoktur. Uygulama bunu algılar: telemetriyi WiFi'dan okurken **harita
karolarını mobil veri üzerinden indirir**. Bunun için **mobil veriyi açık bırakın**. Üst çubuğun
sağında hangi yolun kullanıldığı yazar ("harita: mobil veri" / "harita: internet yok").

Gezdiğiniz harita bölgeleri cihazda önbelleğe alınır (en fazla ~600 MB). Sahada internet yoksa
uçuştan önce evde, internetliyken uçuş bölgesini farklı yakınlaştırma seviyelerinde bir kez gezin.

## Ekran

| Bölge | İçerik |
|---|---|
| Üst çubuk | Uçuş modu, ARM durumu, uçuş süresi, LQ, RSSI, uydu, kayıt durumu (dokununca açılır/kapanır), kaynak IP ve paket hızı |
| Sol panel | Yapay ufuk + pusula, irtifa, vario, yer hızı, ev uzaklığı, eve dönüş yönü (ok: uçağın burnuna göre), GPS irtifası |
| Sağ panel | Pil göstergesi, hücre voltajı, akım, harcanan mAh, güç; SNR, TX gücü, downlink, anten/RF; irtifa ve voltaj grafikleri |
| Orta üst | Uyarılar (kırmızı = kritik) |
| Alt | Koordinat ve kayıt dosyası; düğmeler |

Düğmeler: **⌖** takip modu · **🗺** harita katmanı · **⌂** ev noktasını yeniden al · **⌫** izi temizle ·
**▣** panelleri gizle/göster · **📊** uçuş özeti · **⚙** ayarlar

- **İrtifa**: barometre verisi varsa baro (uçuş kontrolcüsü kalkışta sıfırlar), yoksa GPS irtifası − ev irtifası.
- **Ev noktası**: uydu sayısı 6 ve üstü olan ilk GPS verisinde otomatik alınır.
- **ARM durumu**: Betaflight/INAV disarm iken uçuş modunun sonuna `*` ekler; buna göre gösterilir.
  Bu bilgiyi göndermeyen uçuş kontrolcülerinde "ARM ?" görünür ve uçuş süresi telemetri geldiği sürece sayılır.
- 3 saniyeden eski veriler griye döner.

## Uçuş kaydı

Kayıtlar **İndirilenler / ELRS-Telemetri** klasörüne `ucus_YYYYAAGG_SSDDss.csv` adıyla yazılır
(depolama izni gerekmez). Dosya yöneticisinden açabilir, Excel / Google E-Tablolar'a aktarabilirsiniz.

- Telemetri gelmeye başlayınca kayıt **otomatik** başlar; 30 saniye veri gelmezse dosya kapanır,
  veri tekrar gelince yeni dosya açılır.
- Üst çubuktaki **REC** etiketine dokunarak kaydı elle durdurup başlatabilirsiniz.
- Sütunlar: zaman, enlem, boylam, GPS/göreli/baro irtifa, vario, hız, yön, uydu, pitch/roll/yaw,
  voltaj, hücre voltajı, akım, mAh, kalan %, güç, RSSI 1/2, LQ, SNR, TX gücü, downlink RSSI/LQ,
  uçuş modu, ev uzaklığı, ev yönü.

## Varsayılan ayarlar

| Ayar | Varsayılan |
|---|---|
| Harita katmanı | Uydu |
| Otomatik kayıt | Açık |
| Kayıt sıklığı | 5 Hz (1 / 2 / 5 / 10 Hz seçilebilir) |
| Sesli uyarılar | Açık (15 sn'de bir tekrar) |
| Düşük hücre voltajı uyarısı | 3.50 V |
| Düşük LQ uyarısı | %50 |
| UDP port | 14550 |

Tümü **⚙ Ayarlar**'dan değiştirilebilir. Ayarlar ekranındaki **Ham veri** gelen UDP paketlerini
gösterir (sorun giderme için).

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| Backpack WiFi ağı görünmüyor | Backpack firmware'i 1.5.8+ olmalı (yukarıdaki nota bakın). Telemetry seçeneğini Off → WiFi yapın, birkaç saniye bekleyin. |
| "Telemetri bekleniyor" | Telefon backpack ağına bağlı mı? Android ağdan kopmuş olabilir ("Bağlı kal"). |
| "MAVLink verisi geliyor" | Kumandada Link Mode'u **Normal** yapın. |
| Harita boş/gri | Mobil veri açık mı? Üst çubukta "harita: internet yok" yazıyorsa yalnızca önbellekteki bölgeler görünür. |
| Konum yok | Uçakta GPS ve GPS telemetrisi açık mı, uydu sayısı yeterli mi? |

## Teknik ayrıntılar (kaynak koddan doğrulandı)

- **ELRS TX 3.5.0+**: Lua'daki `Off;ESPNOW;WiFi` seçeneği ve CRSF telemetrisinin backpack'e gönderilmesi
  (`devBackpack.cpp`, `sendCRSFTelemetryToBackpack`). Link Mode MAVLink iken CRSF gönderilmez.
- **Backpack 1.5.8+**: WiFi seçilince backpack ayarı kaydedip yeniden başlar, WiFi'ı AP (veya tanımlıysa
  ev ağı) olarak açar ve her CRSF çerçevesini MSP v2 (`$X<`, fonksiyon `0x0011`) içinde **UDP broadcast**
  olarak **14550** portuna gönderir.
- Ağ adı: `ExpressLRS TX Backpack XXXXXX` (XXXXXX = bind phrase UID'sinin son 3 baytı), şifre
  `expresslrs`, backpack IP `10.0.0.1`.

## APK derleme

- **GitHub Actions**: `main` dalına her gönderimde testler çalışır, APK derlenir ve **Releases**'e eklenir.
- **Android Studio**: klasörü açıp ▶ Run.
- Testler: `./gradlew testDebugUnitTest`

## Kod yapısı

| Dosya | Görev |
|---|---|
| `CrsfParser.java` | CRSF çerçeve tarama, CRC8 (0xD5), telemetri tiplerini çözme |
| `TelemetryState.java` | Son değerler, ev noktası, uzaklık/yön |
| `FlightStats.java` | Uçuş süresi, en yüksek/en düşük değerler, grafik geçmişi |
| `FlightLogger.java` | CSV kaydı (MediaStore → İndirilenler) |
| `GcsMap.java`, `PlaneOverlay.java` | osmdroid harita, katmanlar, iz, uçak/ev çizimi |
| `HorizonView.java`, `BatteryView.java`, `SparklineView.java` | Göstergeler |
| `Alerts.java` | Türkçe sesli uyarılar |
| `MainActivity.java` | UDP alıcı, GCS ekranı, ayarlar |

## Harita verisi

Harita kütüphanesi: [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0).
Sokak haritası © OpenStreetMap katkıcıları; topoğrafik harita © OpenTopoMap (CC-BY-SA);
uydu görüntüsü © Esri, Maxar, Earthstar Geographics. Karo sunucularının kullanım koşulları
kişisel ve düşük hacimli kullanım içindir; toplu indirme yapılmaz.
