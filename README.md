# Radio Paradise Song Tracker

Java 21 Spring Boot uygulaması - Radio Paradise Mellow Mix'de çalan şarkıları otomatik takip eder.

## Özellikler

- ✅ Her 30 saniyede otomatik şarkı kontrolü
- ✅ H2 veritabanına kayıt
- ✅ Play count tracking
- ✅ REST API ile şarkı listesi
- ✅ H2 Console ile veritabanı görüntüleme

## Hızlı Başlangıç

```bash
./mvnw spring-boot:run
```

## API Endpoints

- `GET /api/songs` - Tüm şarkılar
- `GET /api/songs/recent` - Son çalan 20 şarkı
- `GET /api/songs/most-played` - En çok çalan 20 şarkı

## H2 Console

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:radioparadise`
- Kullanıcı: `sa`
- Şifre: (boş)

## Teknolojiler

- Java 21
- Spring Boot 3.4.1
- Spring Data JPA
- H2 Database
- Lombok
- Maven
# radioparadise-tracker
