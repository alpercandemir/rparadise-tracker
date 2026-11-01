# Ücretsiz gözlemlenebilirlik

Bu kurulum mevcut Oracle VM üzerinde çalışan açık kaynak servislerden oluşur.
Yeni VM, ücretli SaaS, ek bulut diski veya ücretli alan adı gerektirmez.
Sunucu/disk/çıkış trafiği toplamı Oracle Always Free sınırlarında tutulmalıdır.

## Grafana'ya erişim

Grafana artık doğrudan HTTPS üzerinden açılır: [Grafana](https://rparadise.138.2.168.194.sslip.io/grafana/).
SSH tüneli gerekmez. Kullanıcı `admin`; mevcut parola sunucudaki `.env` dosyasının
`GRAFANA_ADMIN_PASSWORD` alanında ve yerel `.env.observability-access` dosyasında bulunur.
Bu dosyalar Git'e eklenmez. Giriş zorunludur; anonim erişim ve yeni kullanıcı kaydı kapalıdır.
Prometheus, Loki, exporter ve veritabanı portları internete açılmadı.

Yeni ekranlar:

- [API & Upstream](https://rparadise.138.2.168.194.sslip.io/grafana/d/radioparadise-api): dahili API endpoint/method/status, istek hızı, ortalama/p95/p99 süreler, RP REST çağrıları ve hataları.
- [Oracle VM](https://rparadise.138.2.168.194.sslip.io/grafana/d/radioparadise-vm): CPU, load, RAM, swap, disk/inode, IOPS, gecikme, gerçek VM ağ arayüzü ve kaynak baskısı.
- [Overview](https://rparadise.138.2.168.194.sslip.io/grafana/d/radioparadise-ops): mevcut genel durum ekranı.

RP metrikleri toplam çağrı istatistikleridir; tek tek istek gövdeleri veya trace saklanmaz.
Transport hataları `CLIENT_ERROR`/`IO_ERROR` olarak görünür. HTTP200 sonucunda uygulama verisi bozuksa
collector başarısızlık metriklerini de kontrol et. Sıfır swap normaldir; VM'de swap yapılandırılmamıştır.

Grafana dashboard kaynak tüketimi, uygulama gecikmesi/hataları, JVM,
PostgreSQL ve şarkı toplayıcısının güncelliğini bir arada gösterir.
Explore bölümünde Prometheus ile metrik, Loki ile log sorgulanabilir.

### Uygulama log ekranı

[Application Logs](https://rparadise.138.2.168.194.sslip.io/grafana/d/radioparadise-logs)
ekranını aç. `Log level` filtresinde INFO, WARN, ERROR veya All seç; `Search text`
alanı büyük/küçük harfe duyarlı metin arar. Zaman aralığı sağ üsttedir. Panel
10 saniyede bir yenilenir; her log paneli en fazla 2.000 kayıt getirir. Daha yoğun
bir dönemi incelemek için zaman aralığını daralt.
Belirli seviyeleri seçerken önce seçili `All` etiketini kaldır; ardından bir
veya birkaç seviye seç. `All` seçili kalırsa tüm seviyeler görüntülenir.

Uygulama INFO seviyesinde çalışır: INFO/WARN/ERROR kayıtları üretilir. DEBUG ve
TRACE seçeneği yalnızca uygulama bu seviyelerde kayıt üretirse sonuç verir.
Seviye filtresi geçmiş kayıtları da sorgu sırasında ayrıştırır; seviyesi olmayan
açılış satırları All altında görünür. Yeni Java hata stack trace'leri Alloy'da
tek olayda birleştirilir (256 satır / 3 saniye sınırı). Eski kayıtların satırları
geriye dönük birleştirilmez. All görünümünden veya Explore log context üzerinden
eski hatanın çevresindeki satırlara bakabilirsin.

Canlı akış için Grafana **Explore → Loki** bölümünde
`{job="docker",service="app"}` sorgusunu çalıştırıp **Live** seç.
Log pipeline'ı kayıtları seviyesine göre elemez; saklama süresi 7 gündür.
Tarayıcı hataları bu sunucu loglarına otomatik olarak dahil değildir.

## Neye bakmalı?

| Belirti | İlk kontrol | Sonraki adım |
|---|---|---|
| Site yavaş | HTTP p95, istek/saniye, 5xx | CPU, GC, Hikari bekleyen bağlantı, yavaş SQL |
| Şarkılar güncellenmiyor | Kanal bazında son başarılı poll yaşı | Collector failure sayısı ve app logları |
| RAM yükseliyor | Host available RAM, JVM heap ve GC | Container limit/OOM, sorgu hacmi |
| Veritabanı yavaş | Aktif bağlantı, lock, deadlock, cache hit | 500 ms üstü SQL logları ve pg_stat_statements |
| Disk doluyor | Host disk boşluğu | Docker logları, Loki/Prometheus saklama, DB boyutu |
| HTTPS sorunu | Probe sonucu ve sertifika süresi | Caddy logları ve DNS |

HTTP gecikmesi trafiğe bağlıdır; hiç istek yokken panelin boş görünmesi normaldir.
Collector başarı ölçümü aynı şarkı çalarken de ilerler. Böylece uzun bir şarkı
yanlışlıkla veri toplama arızası sayılmaz. Boş upstream cevap ve DB kayıt hatası
başarısız poll sayılır. Uygulama yeniden başlatılınca counter'lar sıfırlanır;
oran sorguları bunu hesaba katar.

## Terminal üzerinden inceleme

```bash
ssh -i ~/.ssh/id_ed25519 ubuntu@138.2.168.194
cd /home/ubuntu/rparadise-tracker
sudo docker compose -f compose.yaml -f compose.observability.yaml ps
sudo docker stats --no-stream
sudo docker compose logs --since=15m --tail=200 app postgres caddy
free -h
df -h /
```

Grafana Explore / Loki örneği: `{service="app"}`. Hataları süzmek için
`{service="app"} |~ "ERROR|Exception"` kullan. Yeni stack trace'ler tek olayda
toplanır; eski kayıtlarda olayın çevresindeki satırları da incele.

PostgreSQL'in en fazla toplam süre harcayan sorgularını incele:

```bash
sudo docker compose exec postgres psql -U radioparadise -d radioparadise
```

```sql
SELECT queryid, calls, round(total_exec_time::numeric, 1) AS total_ms,
       round(mean_exec_time::numeric, 1) AS mean_ms, rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC LIMIT 10;

SELECT pid, state, wait_event_type, wait_event,
       now() - query_start AS elapsed
FROM pg_stat_activity
WHERE datname = 'radioparadise' AND state <> 'idle';
```

İzleme exporter'ı ayrı `postgres_exporter` hesabını ve `pg_monitor` rolünü
kullanır. Uygulama verilerini değiştirme yetkisi verilmez.

## Saklama ve alarmlar

Prometheus metrikleri 15 gün ve 2 GB blok sınırıyla; Loki logları 7 gün saklar.
WAL/head verisi ve aktif loglar ayrıca disk kullanır; 2 GB toplam disk kotası
değildir. Docker log rotasyonu ayrıca uygulanır. Loki süreye göre temizler,
disk doluluğuna göre otomatik kota uygulamaz; disk alarmı bu yüzden önemlidir.

Alarmlar tek anlık sıçramayla değil belirli süre devam eden sorunlarla tetiklenir.
Host kaynakları, scrape hedefleri, PostgreSQL, HTTP hataları/gecikmesi, TLS
sertifikası ve collector güncelliği izlenir. Alertmanager tekrar eden alarmları
gruplar ve tekrar bildirimlerini sınırlar. Paneldeki alarm görünürlüğü SMTP'den
bağımsızdır.

Kuralların davranışı `observability/prometheus/alerts.test.yml` ile test edilir:
collector açılış toleransı, aynı şarkıda başarılı poll, düşük trafik gürültüsü
ve sürekli HTTP hataları. Yeni kuralları devreye almadan önce bu testleri çalıştır.

E-posta hedefi: `glanceappdev@gmail.com`. Gmail SMTP için iki adımlı doğrulama ve
Google uygulama şifresi gerekir. Normal Google şifresini kullanma. Yerel
`.secrets/smtp_password` dosyası ve sunucudaki karşılığı Git/build dışında tutulur.
SMTP kurulumu ve gerçek gönderim testi tamamlanmadan e-posta bildirimi aktif
olarak kabul edilmemelidir.

Gönderimi etkinleştirmek için `glanceappdev@gmail.com` hesabında
[Google uygulama şifresi](https://myaccount.google.com/apppasswords) oluştur.
İki adımlı doğrulama açık olmalıdır. Proje dizinindeki terminalde aşağıdaki komut
şifreyi ekrana veya komut geçmişine yazmadan dosyaya kaydeder:

```bash
python3 -c 'from pathlib import Path; import getpass; p=Path(".secrets/smtp_password"); p.parent.mkdir(mode=0o700,exist_ok=True); p.touch(mode=0o600,exist_ok=True); p.chmod(0o600); p.write_text(getpass.getpass("Gmail uygulama sifresi: ").replace(" ", "").strip())'
```

Şifreyi sohbete yazma. Dosya hazır olduktan sonra sunucuya güvenli aktarım,
Alertmanager Gmail yapılandırmasının etkinleştirilmesi ve deneme bildirimi gerekir.

## Kurulum doğrulaması — 1 Kasım 2026

- Uygulamanın izleme derlemesinde 13 backend testi ve 4 alarm senaryosu geçti.
- Dokuz Prometheus hedefi erişilebilir; iki HTTPS probe başarılı.
- 21 panelli Grafana dashboard'u tarayıcıda açıldı; Prometheus ve Loki bağlantıları sağlıklı.
- Uygulama, PostgreSQL ve Caddy logları Loki üzerinden sorgulandı.
- Sentetik alarm Alertmanager'a ulaştı ve çözüldü; bu bir e-posta teslim testi değildir.
- Kurulum öncesi yedekteki 12 şarkı ID'sinin tamamı veritabanında korunmuştu.
- Son kaynak ölçümünde yaklaşık 4,4 GiB kullanılabilir bellek ve 35 GiB boş disk vardı.
- E-posta henüz etkin değil: Gmail uygulama şifresi bekleniyor.

## Sınırlar ve bakım

İzleme uygulamayla aynı VM'de: VM tamamen kapanırsa bu kurulum e-posta
gönderemez. Blackbox probe HTTP/DNS/TLS yolunu kontrol eder ama bağımsız bir
lokasyondan uptime izleme değildir. Aynı diskteki izleme verileri ve yedekler
sunucu kaybına karşı koruma sağlamaz. Harici yedek ve bağımsız uptime kontrolü
ayrı operasyonel ihtiyaçlardır.

Güncellemelerde iki Compose dosyasını birlikte kullan:

```bash
sudo docker compose -f compose.yaml -f compose.observability.yaml up -d --build --wait --wait-timeout 300
```

İlk kurulum öncesi DB/config yedeği `/home/ubuntu/backups/pre-observability/`,
önceki uygulama image'ı `rparadise-tracker-app:pre-observability` olarak saklanır.
Geri dönüşte önce sorunlu izleme servislerini durdur, ardından önceki uygulama
image'ını açıkça seçen bir Compose override kullan. Veritabanı volume'ünü silme.
Eski `.env` dosyasını geri yüklemek yeni izleme parolalarını kaldıracağından,
yalnızca gerçekten geri alınması gereken ayarları değiştir.
