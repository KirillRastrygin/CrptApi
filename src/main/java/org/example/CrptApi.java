package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {

    // api честного знака для создания документов
    private static final String CREATE_DOCUMENT_API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final TimeUnit timeUnit;
    private final int requestLimit;

    // Список временных меток запросов
    private final LinkedList<Long> requestTimestamps = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock(true);

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }
        this.httpClient = HttpClient.newHttpClient();
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     *
     * @param authToken токен авторизации
     * @param document объект документа
     * @param signature строка с подписью
     */
    public void createDocumentForCirculation (String authToken, Object document, String signature) throws IOException, InterruptedException {
        // Соблюдаем лимит запросов
        complyRequestLimit();

        // Преобразуем объект документа в JSON
        String documentJson = objectMapper.writeValueAsString(document);

        // Кодируем документ в Base64
        String documentBase64 = Base64.getEncoder().encodeToString(documentJson.getBytes());

        // Формируем тело запроса
        String requestJson = String.format(
                "{ \"product_document\": \"%s\", \"document_format\": \"MANUAL\", \"type\": \"LP_INTRODUCE_GOODS\", \"signature\": \"%s\" }",
                documentBase64, signature);

        // Создаём HTTP-запрос
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CREATE_DOCUMENT_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        // Отправляем запрос
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Проверяем ответ
        int statusCode = response.statusCode();
        if (statusCode == 200 || statusCode == 201) {
            System.out.println("Document successfully created!");
        } else {
            System.err.printf("Failed to create document. Status: %d, Response: %s%n", statusCode, response.body());
        }

    }

    /**
     * Реализация лимита количества запросов в заданный период времени.
     * При превышении лимита поток блокируется.
     */
    private void complyRequestLimit() {
        lock.lock();
        try {
            long now = System.currentTimeMillis(); // Текущее время в миллисекундах
            long intervalMillis = timeUnit.toMillis(1); // Интервал

            // Удаляем устаревшие отметки
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peekFirst() >= intervalMillis) {
                requestTimestamps.removeFirst();
            }

            // Если есть свободное место отправляем новый запрос
            if (requestTimestamps.size() < requestLimit) {
                requestTimestamps.addLast(now);
                return;
            }

            // Иначе ждём, пока освободится место
            long oldestRequestTime = requestTimestamps.peekFirst();
            long waitTime = intervalMillis - (now - oldestRequestTime);

            if (waitTime > 0) {
                // Освобождаем блокировку
                lock.unlock();
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("The thread is interrupted while waiting for the request limit", e);
                } finally {
                    lock.lock();
                }
                // Повторяем процедуру после ожидания
                complyRequestLimit();
            }
        } finally {
            lock.unlock();
        }
    }

    // ==================== Внутренние классы ====================

    /**
     * Класс документа
     */
    public static class Document {
        public Description description;
        public String doc_id;
        public String doc_status;
        public String doc_type;
        public boolean importRequest;
        public String owner_inn;
        public String participant_inn;
        public String producer_inn;
        public String production_date;
        public String production_type;
        public List<Product> products;
        public String reg_date;
        public String reg_number;

        public Document(Description description, String doc_id, String doc_status, String doc_type, boolean importRequest, String owner_inn, String participant_inn, String producer_inn, String production_date, String production_type, List<Product> products, String reg_date, String reg_number) {
            this.description = description;
            this.doc_id = doc_id;
            this.doc_status = doc_status;
            this.doc_type = doc_type;
            this.importRequest = importRequest;
            this.owner_inn = owner_inn;
            this.participant_inn = participant_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.production_type = production_type;
            this.products = products;
            this.reg_date = reg_date;
            this.reg_number = reg_number;
        }
    }

    /**
     * Класс описания к документу
     */
    public static class Description {
        public String participantInn;

        public Description(String participantInn) {
            this.participantInn = participantInn;
        }

    }

    /**
     * Описание товара
     */
    public static class Product {
        public String certificate_document;
        public String certificate_document_date;
        public String certificate_document_number;
        public String owner_inn;
        public String producer_inn;
        public String production_date;
        public String tnved_code;
        public String uit_code;
        public String uitu_code;

        public Product(String certificate_document, String certificate_document_date, String certificate_document_number, String owner_inn, String producer_inn, String production_date, String tnved_code, String uit_code, String uitu_code) {
            this.certificate_document = certificate_document;
            this.certificate_document_date = certificate_document_date;
            this.certificate_document_number = certificate_document_number;
            this.owner_inn = owner_inn;
            this.producer_inn = producer_inn;
            this.production_date = production_date;
            this.tnved_code = tnved_code;
            this.uit_code = uit_code;
            this.uitu_code = uitu_code;
        }
    }


    public static void main(String[] args) throws InterruptedException {
        // Лимит 2 запроса в 1 секунду
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 2);

        // Тестовый токен
        String testToken = "TEST_TOKEN";

        // Создаем тестовый документ
        CrptApi.Description description = new CrptApi.Description("1234567890");
        CrptApi.Product product = new CrptApi.Product(
                "CERT", "2025-10-27", "12345", "1111111111",
                "2222222222", "2025-10-27", "123456", "UIT001", "UITU001"
        );
        CrptApi.Document document = new CrptApi.Document(
                description,
                "doc1",
                "NEW",
                "LP",
                false,
                "1111111111",
                "2222222222",
                "3333333333",
                "2025-10-27",
                "TYPE1",
                Collections.singletonList(product),
                "2025-10-27",
                "REG001"
        );

        // Подпись Base64
        String signature = "TEST_SIGNATURE";

        // Создаем пул потоков
        ExecutorService executor = Executors.newFixedThreadPool(5);

        // Запускаем 5 потоков, чтобы проверить лимит запросов
        for (int i = 0; i < 5; i++) {
            int threadNumber = i;
            executor.submit(() -> {
                try {
                    // Кодируем документ в Base64
                    String json = api.objectMapper.writeValueAsString(document);
                    String base64Document = Base64.getEncoder().encodeToString(json.getBytes());

                    api.createDocumentForCirculation(testToken, base64Document, signature);
                    System.out.println("Thread " + threadNumber + " finished request");
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        System.out.println("All threads finished.");
    }

}