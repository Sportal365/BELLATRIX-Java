/*
 * Copyright 2022 Automate The Planet Ltd.
 * Author: Anton Angelov
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package solutions.bellatrix.core.utilities.mail;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import solutions.bellatrix.core.utilities.mail.models.EmailsItem;
import solutions.bellatrix.core.utilities.mail.models.EmailsResponse;

import java.util.List;
import java.util.stream.Collectors;

public class TestmailService {
    private final String EMAIL_SERVICE_URL = "https://api.testmail.app/";
    private String apiKey;
    private String namespace;

    public TestmailService(String apiKey, String namespace) {
        this.apiKey = apiKey;
        this.namespace = namespace;
    }

    public String loadEmailBody(java.util.function.Consumer<String> loadContent, String htmlBody) {
        htmlBody = htmlBody.replace("\n", "").replace("\\/", "/").replace("\\\"", "\"");
        loadContent.accept(htmlBody);
        return htmlBody;
    }


    public EmailsItem getLastSentEmail(String inboxName) {
        var allEmails = getAllEmails();
        var sortedEmails = allEmails.getEmails().stream().filter(e -> e.getEnvelopeTo().contains(inboxName)).sorted().collect(Collectors.toList());
        return sortedEmails.get((int) (sortedEmails.stream().count() - 1));
    }

    public List<EmailsItem> getAllEmails(String inboxName) {
        var allEmails = getAllEmails();

        return allEmails.getEmails().stream().filter(e -> e.getEnvelopeTo().contains(inboxName)).collect(Collectors.toList());
    }

    private EmailsResponse getAllEmails() {
        var emailsResponse = RestAssured.given()
                .baseUri(EMAIL_SERVICE_URL)
                .log().all()
                .contentType(ContentType.JSON)
                .queryParam("apikey", apiKey)
                .queryParam("namespace", namespace)
                .queryParam("pretty", "true")
                .get("/api/json/").as(EmailsResponse.class);
        return emailsResponse;
    }
}
