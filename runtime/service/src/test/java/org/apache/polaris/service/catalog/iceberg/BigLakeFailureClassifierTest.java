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

import jakarta.ws.rs.core.Response.Status;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.polaris.service.exception.BigLakeFailureCategory;
import org.apache.polaris.service.exception.BigLakeFederationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BigLakeFailureClassifierTest {
  static Stream<Arguments> classifiesFailures() {
    return Stream.of(
        Arguments.of(
            BigLakeOperationType.CONFIG,
            new RuntimeException("unauthenticated"),
            error(401, "Unauthorized", "Request was unauthenticated"),
            BigLakeFailureCategory.AUTHENTICATION,
            Status.UNAUTHORIZED.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.LOAD_TABLE,
            new RuntimeException("permission denied"),
            error(403, "Forbidden", "Permission denied for the remote catalog"),
            BigLakeFailureCategory.AUTHORIZATION,
            Status.FORBIDDEN.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.CONFIG,
            new RuntimeException("quota"),
            error(400, "BadRequest", "Missing quota project x-goog-user-project"),
            BigLakeFailureCategory.QUOTA_PROJECT_CONFIGURATION,
            Status.BAD_REQUEST.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.UPDATE_TABLE,
            new RuntimeException("unsupported"),
            error(405, "MethodNotAllowed", "Unsupported operation"),
            BigLakeFailureCategory.UNSUPPORTED_OPERATION,
            Status.NOT_ACCEPTABLE.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.CONFIG,
            new RuntimeException("not found"),
            error(404, "NotFound", "Catalog missing"),
            BigLakeFailureCategory.INVALID_REMOTE_CATALOG,
            Status.BAD_REQUEST.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.LOAD_TABLE,
            new RuntimeException(new SocketTimeoutException("timed out")),
            null,
            BigLakeFailureCategory.TIMEOUT,
            Status.SERVICE_UNAVAILABLE.getStatusCode()),
        Arguments.of(
            BigLakeOperationType.LOAD_TABLE,
            new RuntimeException(new UnknownHostException("biglake.googleapis.com")),
            null,
            BigLakeFailureCategory.DNS_FAILURE,
            Status.SERVICE_UNAVAILABLE.getStatusCode()));
  }

  @ParameterizedTest
  @MethodSource
  void classifiesFailures(
      BigLakeOperationType operation,
      RuntimeException failure,
      ErrorResponse remoteError,
      BigLakeFailureCategory expectedCategory,
      int expectedCode) {
    Optional<BigLakeFederationException> classified =
        BigLakeFailureClassifier.classify(
            operation,
            failure,
            remoteError,
            Map.of("x-goog-request-id", "req-123", "Retry-After", "60"),
            2);

    assertThat(classified)
        .isPresent()
        .get()
        .satisfies(
            exception -> {
              assertThat(exception.category()).isEqualTo(expectedCategory);
              assertThat(exception.responseCode()).isEqualTo(expectedCode);
              assertThat(exception.googleRequestId()).isEqualTo("req-123");
            });
  }

  @Test
  void missingTableRemainsUnclassified() {
    assertThat(
            BigLakeFailureClassifier.classify(
                BigLakeOperationType.LOAD_TABLE,
                new NoSuchTableException("missing"),
                error(404, "NotFound", "table missing"),
                Map.of(),
                0))
        .isEmpty();
  }

  private static ErrorResponse error(int code, String type, String message) {
    return ErrorResponse.builder().responseCode(code).withType(type).withMessage(message).build();
  }
}
