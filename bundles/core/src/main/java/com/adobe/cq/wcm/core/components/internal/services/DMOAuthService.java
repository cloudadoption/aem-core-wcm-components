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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Acquires and caches an IMS OAuth Server-to-Server access token, used to make authenticated calls to the
 * Dynamic Media metadata API for vanity asset id resolution. Never throws - callers get {@link Optional#empty()}
 * on any failure, so a token/network problem degrades to "no vanity id" rather than breaking the caller.
 */
@Component(service = DMOAuthService.class)
@Designate(ocd = DMOAuthConfig.class)
public class DMOAuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DMOAuthService.class);

    // Refresh this long before the token's actual expiry, so a request never races a token that expires mid-call.
    private static final long EXPIRY_BUFFER_MILLIS = 60_000;

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    private volatile DMOAuthConfig config;
    private volatile CloseableHttpClient httpClient;

    private final ObjectMapper mapper = new ObjectMapper();

    // Guards cachedToken/cachedTokenExpiryMillis - token exchanges are infrequent (roughly once per token
    // lifetime), so contention on this lock is not a concern.
    private final Object tokenLock = new Object();
    private String cachedToken;
    private long cachedTokenExpiryMillis;

    @Activate
    @Modified
    protected void activate(DMOAuthConfig config) {
        CloseableHttpClient previousClient = this.httpClient;
        this.httpClient = buildHttpClient(config);
        this.config = config;
        synchronized (tokenLock) {
            // Config change may mean new credentials - drop any cached token so the next call re-authenticates.
            cachedToken = null;
            cachedTokenExpiryMillis = 0;
        }
        if (previousClient != null) {
            closeQuietly(previousClient);
        }
    }

    @Deactivate
    protected void deactivate() {
        closeQuietly(this.httpClient);
        this.httpClient = null;
    }

    /**
     * @return a cached or freshly-exchanged IMS access token, or {@link Optional#empty()} if one could not be
     *         obtained (missing config, network error, non-2xx response, malformed response).
     */
    public Optional<String> getAccessToken() {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis();
            if (StringUtils.isNotBlank(cachedToken) && now < cachedTokenExpiryMillis - EXPIRY_BUFFER_MILLIS) {
                return Optional.of(cachedToken);
            }
            return exchangeToken(now);
        }
    }

    private Optional<String> exchangeToken(long now) {
        DMOAuthConfig config = this.config;
        if (config == null) {
            LOGGER.warn("Dynamic Media OAuth service has no configuration at all (OSGi config not created)");
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (StringUtils.isBlank(config.clientId())) {
            missing.add("clientId");
        }
        if (StringUtils.isBlank(config.clientSecret())) {
            missing.add("clientSecret");
        }
        if (StringUtils.isBlank(config.scope())) {
            missing.add("scope");
        }
        if (StringUtils.isBlank(config.tokenEndpoint())) {
            missing.add("tokenEndpoint");
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("Dynamic Media OAuth service is missing required config field(s): {}; cannot resolve vanity "
                + "asset ids", missing);
            return Optional.empty();
        }
        CloseableHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            LOGGER.warn("Dynamic Media OAuth HTTP client is not available (component deactivated)");
            return Optional.empty();
        }
        LOGGER.info("Exchanging IMS OAuth Server-to-Server token at {} (clientId={}, scope={})",
            config.tokenEndpoint(), config.clientId(), config.scope());
        try {
            HttpPost post = new HttpPost(config.tokenEndpoint());
            post.setHeader("Accept", "application/json");
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("grant_type", "client_credentials"));
            params.add(new BasicNameValuePair("client_id", config.clientId()));
            params.add(new BasicNameValuePair("client_secret", config.clientSecret()));
            params.add(new BasicNameValuePair("scope", config.scope()));
            post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (statusCode != HttpStatus.SC_OK) {
                    // IMS error bodies (invalid_client, invalid_scope, etc.) never include the secret - safe to log in full.
                    LOGGER.warn("IMS token exchange failed with status {}: {}", statusCode, body);
                    return Optional.empty();
                }
                JsonNode json = mapper.readTree(body);
                String accessToken = json.path("access_token").asText(null);
                if (StringUtils.isBlank(accessToken)) {
                    LOGGER.warn("IMS token exchange returned HTTP 200 but no access_token; response: {}", body);
                    return Optional.empty();
                }
                long expiresInMillis = json.path("expires_in").asLong(0) * 1000;
                cachedToken = accessToken;
                cachedTokenExpiryMillis = expiresInMillis > 0 ? now + expiresInMillis : 0;
                LOGGER.info("IMS token exchange succeeded, expires in {}s", json.path("expires_in").asLong(0));
                return Optional.of(accessToken);
            }
        } catch (IOException e) {
            LOGGER.warn("IMS token exchange failed", e);
            return Optional.empty();
        }
    }

    private void closeQuietly(CloseableHttpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            LOGGER.warn("Failed to close Dynamic Media OAuth HTTP client", e);
        }
    }

    protected CloseableHttpClient buildHttpClient(DMOAuthConfig config) {
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(config.connectionTimeout())
            .setSocketTimeout(config.socketTimeout())
            .build();
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().setDefaultRequestConfig(requestConfig).build();
        }
        return HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
    }
}
