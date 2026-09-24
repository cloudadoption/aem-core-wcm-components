/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Copyright 2026 Adobe
 ~
 ~ Licensed under the Apache License, Version 2.0 (the "License");
 ~ you may not use this file except in compliance with the License.
 ~ You may obtain a copy of the License at
 ~
 ~     http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing, software
 ~ distributed under the License is distributed on an "AS IS" BASIS,
 ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ~ See the License for the specific language governing permissions and
 ~ limitations under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package com.adobe.cq.wcm.core.components.internal.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DMOAuthServiceTest {

    private static final String TEST_CLIENT_ID = "test-client-id";
    private static final String TEST_CLIENT_SECRET = "test-client-secret";
    private static final String TEST_SCOPE = "openid,AdobeID";
    private static final String TEST_TOKEN_ENDPOINT = "http://test.ims.example.com/token";

    private DMOAuthService service;
    private CloseableHttpClient mockHttpClient;
    private CloseableHttpResponse mockResponse;

    @BeforeEach
    void setUp() {
        service = new DMOAuthService();
        service.activate(config(TEST_CLIENT_ID, TEST_CLIENT_SECRET, TEST_SCOPE, TEST_TOKEN_ENDPOINT));
        mockHttpClient = mock(CloseableHttpClient.class);
        mockResponse = mock(CloseableHttpResponse.class);
        attachTransport(service);
    }

    private DMOAuthConfig config(String clientId, String clientSecret, String scope, String tokenEndpoint) {
        return new DMOAuthConfig() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }

            @Override
            public String clientId() {
                return clientId;
            }

            @Override
            public String clientSecret() {
                return clientSecret;
            }

            @Override
            public String scope() {
                return scope;
            }

            @Override
            public String tokenEndpoint() {
                return tokenEndpoint;
            }

            @Override
            public int connectionTimeout() {
                return 1000;
            }

            @Override
            public int socketTimeout() {
                return 1000;
            }
        };
    }

    private void attachTransport(DMOAuthService target) {
        // The client is built once in activate() - injecting a mock HttpClientBuilderFactory after activate() has
        // already run would be too late, so set the already-built (real) client field directly instead.
        setField(DMOAuthService.class, "httpClient", target, mockHttpClient);
    }

    private void respondWith(int statusCode, String jsonBody) throws IOException {
        StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, "");
        when(mockResponse.getStatusLine()).thenReturn(statusLine);
        HttpEntity mockEntity = mock(HttpEntity.class);
        when(mockEntity.getContent()).thenReturn(new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8)));
        when(mockResponse.getEntity()).thenReturn(mockEntity);
        when(mockHttpClient.execute(any(HttpUriRequest.class))).thenReturn(mockResponse);
    }

    private HttpPost captureExecutedRequest() throws IOException {
        ArgumentCaptor<HttpUriRequest> captor = ArgumentCaptor.forClass(HttpUriRequest.class);
        verify(mockHttpClient).execute(captor.capture());
        return (HttpPost) captor.getValue();
    }

    @Test
    void cachedTokenReusedWithoutNetworkCall() throws Exception {
        setField(DMOAuthService.class, "cachedToken", service, "cached-token");
        setField(DMOAuthService.class, "cachedTokenExpiryMillis", service, System.currentTimeMillis() + 3_600_000);

        Optional<String> token = service.getAccessToken();

        assertEquals(Optional.of("cached-token"), token);
        verify(mockHttpClient, never()).execute(any(HttpUriRequest.class));
    }

    @Test
    void successfulExchangeCachesTokenForSubsequentCalls() throws Exception {
        respondWith(200, "{\"access_token\":\"fresh-token\",\"expires_in\":86400}");

        Optional<String> first = service.getAccessToken();
        Optional<String> second = service.getAccessToken();

        assertEquals(Optional.of("fresh-token"), first);
        assertEquals(Optional.of("fresh-token"), second);
        verify(mockHttpClient, times(1)).execute(any(HttpUriRequest.class));
    }

    @Test
    void tokenRequestBodyContainsGrantTypeAndCredentials() throws Exception {
        respondWith(200, "{\"access_token\":\"fresh-token\",\"expires_in\":86400}");

        service.getAccessToken();

        HttpPost sent = captureExecutedRequest();
        String body = new String(sent.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("grant_type=client_credentials"));
        assertTrue(body.contains("client_id=" + TEST_CLIENT_ID));
        assertTrue(body.contains("client_secret=" + TEST_CLIENT_SECRET));
    }

    @Test
    void missingConfigFieldReturnsEmptyWithoutNetworkCall() throws Exception {
        service.activate(config(TEST_CLIENT_ID, "", TEST_SCOPE, TEST_TOKEN_ENDPOINT));
        attachTransport(service);

        Optional<String> token = service.getAccessToken();

        assertFalse(token.isPresent());
        verify(mockHttpClient, never()).execute(any(HttpUriRequest.class));
    }

    @Test
    void nonOkResponseReturnsEmpty() throws Exception {
        respondWith(401, "{\"error\":\"invalid_client\"}");

        Optional<String> token = service.getAccessToken();

        assertFalse(token.isPresent());
    }

    @Test
    void blankAccessTokenInResponseReturnsEmpty() throws Exception {
        respondWith(200, "{\"expires_in\":86400}");

        Optional<String> token = service.getAccessToken();

        assertFalse(token.isPresent());
    }

    @Test
    void ioExceptionDuringExchangeReturnsEmpty() throws Exception {
        when(mockHttpClient.execute(any(HttpUriRequest.class))).thenThrow(new IOException("network down"));

        Optional<String> token = service.getAccessToken();

        assertFalse(token.isPresent());
    }

    @Test
    void deactivateClosesHttpClientAndClearsField() throws Exception {
        service.deactivate();

        verify(mockHttpClient).close();
        assertNull(FieldUtils.getField(DMOAuthService.class, "httpClient", true).get(service));
    }

    @Test
    void reactivateClosesPreviousClientAndDropsCachedToken() throws Exception {
        setField(DMOAuthService.class, "cachedToken", service, "stale-token");

        service.activate(config(TEST_CLIENT_ID, TEST_CLIENT_SECRET, TEST_SCOPE, TEST_TOKEN_ENDPOINT));

        verify(mockHttpClient).close();
        assertNull(FieldUtils.getField(DMOAuthService.class, "cachedToken", true).get(service));
    }

    @Test
    void usesDefaultHttpClientWhenBuilderFactoryMissing() {
        DMOAuthService noFactory = new DMOAuthService();
        setField(DMOAuthService.class, "httpClientBuilderFactory", noFactory, null);

        assertTrue(noFactory.buildHttpClient(config(TEST_CLIENT_ID, TEST_CLIENT_SECRET, TEST_SCOPE, TEST_TOKEN_ENDPOINT))
            != null);
    }

    public static void setField(@NotNull final Class<?> clazz,
                                 @NotNull final String fieldName,
                                 @Nullable final Object target,
                                 @Nullable final Object value) {
        final Field f = FieldUtils.getField(clazz, fieldName, true);
        FieldUtils.removeFinalModifier(f);
        try {
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
