/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.catalog.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.RESTResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.polaris.service.exception.BigLakeFailureCategory;
import org.apache.polaris.service.exception.BigLakeFederationException;
import org.junit.jupiter.api.Test;

class BigLakeFederatedRestClientTest {
  @Test
  void recordsSuccessMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeRestClient delegate = new FakeRestClient();
    delegate.response = new TestResponse();

    BigLakeFederatedRestClient client =
        new BigLakeFederatedRestClient(
            delegate,
            registry,
            "local-catalog",
            Map.of(
                org.apache.iceberg.CatalogProperties.URI,
                "https://biglake.googleapis.com/iceberg/v1/restcatalog"));

    TestResponse response =
        client.get(
            "/v1/config",
            Map.of(),
            TestResponse.class,
            Map.of(),
            error -> {
              throw new RuntimeException(error.message());
            });

    assertThat(response).isNotNull();
    assertThat(
            registry
                .find("polaris.federation.biglake.requests")
                .tags(
                    "operation",
                    "CONFIG",
                    "remote_host",
                    "biglake.googleapis.com",
                    "response_status",
                    "200",
                    "failure_category",
                    "NONE",
                    "outcome",
                    "success")
                .counter())
        .isNotNull();
    assertThat(registry.find("polaris.federation.biglake.request.latency").timers()).hasSize(1);
  }

  @Test
  void redactsSecretsAndRecordsFailureMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeRestClient delegate = new FakeRestClient();
    delegate.errorResponse =
        ErrorResponse.builder()
            .responseCode(400)
            .withType("BadRequest")
            .withMessage("Missing quota project. Authorization: Bearer super-secret-token")
            .build();
    delegate.responseHeaders = Map.of("x-goog-request-id", "req-1", "Retry-After", "120");

    BigLakeFederatedRestClient client =
        new BigLakeFederatedRestClient(
            delegate,
            registry,
            "local-catalog",
            Map.of(
                org.apache.iceberg.CatalogProperties.URI,
                "https://biglake.googleapis.com/iceberg/v1/restcatalog"));

    assertThatThrownBy(
            () ->
                client.get(
                    "/v1/config",
                    Map.of(),
                    TestResponse.class,
                    Map.of(),
                    error -> {
                      throw new RuntimeException(error.message());
                    }))
        .isInstanceOf(BigLakeFederationException.class)
        .satisfies(
            throwable -> {
              BigLakeFederationException exception = (BigLakeFederationException) throwable;
              assertThat(exception.category())
                  .isEqualTo(BigLakeFailureCategory.QUOTA_PROJECT_CONFIGURATION);
              assertThat(exception.message()).doesNotContain("super-secret-token");
              assertThat(exception.retryAfter()).isEqualTo("120");
            });

    assertThat(
            registry
                .find("polaris.federation.biglake.requests")
                .tags(
                    "operation",
                    "CONFIG",
                    "remote_host",
                    "biglake.googleapis.com",
                    "response_status",
                    "400",
                    "failure_category",
                    "QUOTA_PROJECT_CONFIGURATION",
                    "outcome",
                    "failure")
                .counter())
        .isNotNull();
  }

  @Test
  void classifiesQuotaExhaustionAsRetryable() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeRestClient delegate = new FakeRestClient();
    delegate.errorResponse =
        ErrorResponse.builder()
            .responseCode(429)
            .withType("TooManyRequests")
            .withMessage("quota exceeded for billing project")
            .build();
    delegate.responseHeaders = Map.of("Retry-After", "30");

    BigLakeFederatedRestClient client =
        new BigLakeFederatedRestClient(
            delegate,
            registry,
            "local-catalog",
            Map.of(
                org.apache.iceberg.CatalogProperties.URI,
                "https://biglake.googleapis.com/iceberg/v1/restcatalog"));

    assertThatThrownBy(
            () ->
                client.get(
                    "/v1/namespaces/ns/tables/table",
                    Map.of(),
                    TestResponse.class,
                    Map.of(),
                    error -> {
                      throw new RuntimeException(error.message());
                    }))
        .isInstanceOf(BigLakeFederationException.class)
        .satisfies(
            throwable -> {
              BigLakeFederationException exception = (BigLakeFederationException) throwable;
              assertThat(exception.category()).isEqualTo(BigLakeFailureCategory.QUOTA_EXHAUSTED);
              assertThat(exception.category().retryable()).isTrue();
              assertThat(exception.retryAfter()).isEqualTo("30");
            });
  }

  @Test
  void wrapsUnknownFailuresInSanitizedBigLakeException() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeRestClient delegate = new FakeRestClient();
    delegate.runtimeFailure =
        new RuntimeException("Malformed payload Authorization: Bearer super-secret-token");
    delegate.responseHeaders = Map.of("x-goog-request-id", "req-9");

    BigLakeFederatedRestClient client =
        new BigLakeFederatedRestClient(
            delegate,
            registry,
            "local-catalog",
            Map.of(
                org.apache.iceberg.CatalogProperties.URI,
                "https://biglake.googleapis.com/iceberg/v1/restcatalog"));

    assertThatThrownBy(
            () ->
                client.get(
                    "/v1/namespaces/ns/tables/table",
                    Map.of(),
                    TestResponse.class,
                    Map.of(),
                    error -> {
                      throw new RuntimeException(error.message());
                    }))
        .isInstanceOf(BigLakeFederationException.class)
        .satisfies(
            throwable -> {
              BigLakeFederationException exception = (BigLakeFederationException) throwable;
              assertThat(exception.category()).isEqualTo(BigLakeFailureCategory.UNKNOWN);
              assertThat(exception.errorType()).isEqualTo("BigLakeUnknownException");
              assertThat(exception.getMessage())
                  .contains("unexpected response")
                  .contains("req-9")
                  .doesNotContain("super-secret-token");
            });

    assertThat(
            registry
                .find("polaris.federation.biglake.requests")
                .tags(
                    "operation",
                    "LOAD_TABLE",
                    "remote_host",
                    "biglake.googleapis.com",
                    "response_status",
                    "500",
                    "failure_category",
                    "UNKNOWN",
                    "outcome",
                    "failure")
                .counter())
        .isNotNull();
  }

  @Test
  void sanitizeRemoteDetailRedactsCommonSecrets() {
    String sanitized =
        BigLakeFederatedRestClient.sanitizeRemoteDetail(
            "Authorization: Bearer test-token?access_token=abc&client_secret=def");

    assertThat(sanitized).doesNotContain("test-token").doesNotContain("abc").doesNotContain("def");
    assertThat(sanitized).contains("<redacted>");
  }

  private static final class FakeRestClient implements RESTClient {
    private TestResponse response;
    private ErrorResponse errorResponse;
    private RuntimeException runtimeFailure;
    private Map<String, String> responseHeaders = Map.of();

    @Override
    public void head(String path, Map<String, String> headers, Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T delete(
        String path,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    public <T extends RESTResponse> T get(
        String path,
        Map<String, String> queryParams,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler,
        Consumer<Map<String, String>> responseHeadersConsumer) {
      responseHeadersConsumer.accept(responseHeaders);
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      if (errorResponse != null) {
        errorHandler.accept(errorResponse);
      }
      return responseType.cast(response);
    }

    @Override
    public <T extends RESTResponse> T post(
        String path,
        RESTRequest body,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends RESTResponse> T postForm(
        String path,
        Map<String, String> formData,
        Class<T> responseType,
        Map<String, String> headers,
        Consumer<ErrorResponse> errorHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  private static final class TestResponse implements RESTResponse {
    @Override
    public void validate() {}
  }
}
