# SOYO Mesaj Yardımcısı v3.0 — sıfırdan altyapı

Bu proje v2.x servisinin üzerine yama değildir. Otomasyon akışı sıfırdan, küçük ve belirgin bir durum makinesiyle yeniden yazıldı.

## Neden v3?

Önceki yapıda aynı anda accessibility eventleri, gecikmeli callback'ler, watchdog ve eski ekrandan taşınan node referansları birbiriyle yarışabiliyordu. Bunun görünen sonuçları aynı sohbete yeniden girme, aç/kapa sonrasında eski işlemin devam etmesi ve yeni sohbet ekranının elle kaydırılana kadar güncellenmemesi olabiliyordu.

v3'te temel kural şudur: **AccessibilityNodeInfo nesnesi bir sonraki adıma taşınmaz.** Her adım aktif pencereyi yeniden okur ve yalnızca isim, mesaj ve durum gibi sade verileri saklar.

## Yeni otomasyon akışı

Durumlar:

1. `LIST` — Önerilenler ekranını okur.
2. `OPENING_CHAT` — seçilen kişinin sohbetinin gerçekten açılmasını bekler.
3. `CHAT_READY` — üst başlıktaki isim ile listeden taşınan ismi doğrular.
4. Mesaj kutusu boşsa şablonu yazar. Kullanıcı taslağı varsa üzerine yazmaz.
5. `VERIFYING_SEND` — gönder düğmesine **yalnızca bir kere** dokunur ve sonucu doğrular.
6. `RETURNING` — yalnızca doğrulanmış sohbet ekranından listeye geri döner.
7. Listeye dönünce işlenmiş kişi atlanır ve sıradaki kişi seçilir.

## Duplicate / aynı sohbete tekrar girme koruması

- Gönderilen veya güvenli biçimde atlanan isimler 8 saat SharedPreferences içinde tutulur.
- Servis kapatılıp açılsa bile bu geçmiş korunur.
- Tam otomatik modu aç/kapa yapmak mevcut sohbet durumunu tamamen sıfırlar.
- Bir sohbet satırına tıklandıktan sonra sohbet başlığı listedeki isimle eşleşmeden mesaj gönderilmez.
- Gönder düğmesine bir kez kabul edilmiş dokunuş yapıldıktan sonra aynı sohbet içinde ikinci otomatik gönder dokunuşu yapılmaz.
- Gönderim sonucu erişilebilirlik ağacında geç görünürse kişi tekrar mesaj gitmesin diye işlenmiş sayılır ve listeden devam edilir.

## Yeni sohbet ekranının takılması

SOYO/Flutter bazen sohbet değiştiği halde accessibility ağacında önceki başlığı kısa süre tutabilir. v3:

- doğru başlığı bekler,
- mesajı yanlış başlıkta göndermez,
- ekran güncellenmezse mesaj geçmişi alanında küçük bir otomatik `nudge` kaydırması yapar,
- en fazla iki kez dener,
- hâlâ doğrulanmazsa o sohbeti güvenli şekilde atlayıp listeye döner.

Bu, elle kaydırma ihtiyacını azaltmak için doğrudan yeni altyapının parçasıdır.

## Liste kaydırma

Görünür uygun kişi kalmayınca önce erişilebilirlik `ACTION_SCROLL_FORWARD` denenir. Liste snapshot'ı değişmezse gerçek swipe gesture yedeği kullanılır. Aynı ekran uzun süre değişmezse daha güçlü swipe ve Önerilenler sekmesini yeniden seçme kurtarması devreye girer.

## Arayüz

Ana ekran yeniden tasarlandı:

- servis açık/kapalı durumu,
- canlı otomasyon durumu,
- son 8 saat tekrar korumasındaki kişi sayısı,
- Tam Otomatik Mod,
- Sohbeti otomatik aç,
- Sadece VIP,
- hitap seçimi,
- mesaj şablonu ve canlı önizleme,
- işlenen geçmişini temizleme,
- SOYO'yu açma düğmesi.

## Mesaj şablonu

Varsayılan:

```text
{isim} {hitap} selamlarrrr
```

Hitap seçenekleri: `Bey`, `Hanım`, `Hitap yok`.

## Tek onay modu

Tam Otomatik Mod kapalıyken sohbet ekranında isim doğrulanır ve mesaj kutusuna mesaj hazırlanır. Alt tarafta erişilebilirlik overlay'i açılır. `Gönder` veya `Atla` seçilebilir.

## Güvenlik kuralları

- Servis yalnızca `com.haflla.soulu` ve `com.haflla.soulu.lite` paketlerini dinler.
- Tam otomatik mod botun kendisinin açmadığı bir sohbete otomatik mesaj göndermez.
- Mesaj kutusunda mevcut kullanıcı taslağı varsa üzerine yazılmaz.
- İsim için `VIP`, `TAG`, `ID`, km/yaş, rozet ve benzeri metinler filtrelenir.
- Şekilli Unicode isimler mümkün olduğunca normal Latin/Türkçe ada çevrilir.

## APK üretme — GitHub Codespaces / Actions

Projeyi repository köküne koyup push ettikten sonra workflow otomatik çalışır:

```bash
git add -A
git commit -m "v3 sifirdan otomasyon altyapisi"
git push
```

GitHub → **Actions → Build Android APK** bölümünde artifact adı:

```text
SoyoMesajYardimcisi-v3-debug-apk
```

İçinde `app-debug.apk` bulunur.

Yerelde Android Studio ile de `Build > Build APK(s)` kullanılabilir.

## Not

SOYO'nun erişilebilirlik ağacı uygulama güncellemeleriyle değişebilir. v3 eski sürüme göre daha az ekran konumuna bağımlıdır; yine de SOYO arayüzü kökten değişirse satır/başlık algılama kurallarının güncellenmesi gerekebilir.

## v3.0.1 build fix
GitHub Actions derlemesi Android API 36 ve Build Tools 35.0.0'ı açıkça kurar. Gradle 8.13 ve JDK 17 kullanır. Derleme başarısız olursa `gradle-build-log` artifact'i de yüklenir.
