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
package com.adobe.cq.wcm.core.components.internal.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.ui.wcm.commons.config.NextGenDynamicMediaConfig;
import com.adobe.cq.wcm.core.components.internal.services.DMOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Author-only endpoint that resolves a vanity asset id: given a real NGDM asset id and a metadata property name,
 * it makes an authenticated call to the Dynamic Media metadata API and returns the property's value, if present.
 * Every failure mode (no token, network error, property absent, malformed response) returns an empty response
 * rather than an error - callers treat "no value" as the normal/expected outcome of a cache miss or a property
 * that simply isn't set on this asset.
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/wcm/core/components/image/v3/vanityid",
        "sling.servlet.methods=GET"
    }
)
public class VanityIdResolverServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(VanityIdResolverServlet.class);

    private static final String PARAM_ASSET_ID = "assetId";
    private static final String PARAM_PROPERTY = "property";
    private static final String ASSET_METADATA_KEY = "assetMetadata";
    private static final String EMBEDDED_METADATA_KEY = "http://ns.adobe.com/adobecloud/rel/metadata/embedded";
    private static final String ANONYMOUS_USER_ID = "anonymous";

    private static final int CONNECTION_TIMEOUT = 2000;
    private static final int SOCKET_TIMEOUT_MILLIS = 10000;

    @Reference
    private DMOAuthService dmOAuthService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile NextGenDynamicMediaConfig nextGenDynamicMediaConfig;

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response)
        throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (ANONYMOUS_USER_ID.equals(request.getResourceResolver().getUserID())) {
            response.setStatus(HttpStatus.SC_FORBIDDEN);
            return;
        }

        String assetId = request.getParameter(PARAM_ASSET_ID);
        String property = request.getParameter(PARAM_PROPERTY);
        if (StringUtils.isBlank(assetId) || StringUtils.isBlank(property)) {
            response.setStatus(HttpStatus.SC_BAD_REQUEST);
            return;
        }

        Optional<String> vanityId = resolveVanityId(assetId, property);
        if (vanityId.isPresent()) {
            response.getWriter().write(mapper.createObjectNode().put("vanityId", vanityId.get()).toString());
        }
        // No value found (any reason): empty 200 response - the client treats this as "no vanity id available".
    }

    private Optional<String> resolveVanityId(String assetId, String property) {
        Optional<String> accessToken = dmOAuthService.getAccessToken();
        if (!accessToken.isPresent()) {
            return Optional.empty();
        }
        String repositoryId = nextGenDynamicMediaConfig != null ? nextGenDynamicMediaConfig.getRepositoryId() : null;
        if (StringUtils.isBlank(repositoryId)) {
            LOGGER.warn("Dynamic Media repository id is not configured; cannot resolve vanity asset id");
            return Optional.empty();
        }

        String url = "https://" + repositoryId + "/adobe/assets/" + assetId + "/metadata";
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(CONNECTION_TIMEOUT)
            .setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
            .build();
        CloseableHttpClient httpClient = httpClientBuilderFactory != null
            ? httpClientBuilderFactory.newBuilder().setDefaultRequestConfig(requestConfig).build()
            : HttpClients.custom().setDefaultRequestConfig(requestConfig).build();

        LOGGER.info("Fetching Dynamic Media metadata for asset {} to resolve vanity id property '{}' ({})",
            assetId, property, url);
        try {
            HttpGet get = new HttpGet(url);
            get.setHeader("Accept", "application/json");
            get.setHeader("Authorization", "Bearer " + accessToken.get());
            try (CloseableHttpResponse response = httpClient.execute(get)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (statusCode != HttpStatus.SC_OK) {
                    LOGGER.warn("Dynamic Media metadata request for {} failed with status {}: {}", assetId, statusCode, body);
                    return Optional.empty();
                }
                // Full metadata body, at DEBUG - useful while verifying which property (e.g. now:vanityId) actually
                // carries the vanity value and where it lands (top-level vs _embedded) before switching over.
                LOGGER.debug("Dynamic Media metadata response for asset {}: {}", assetId, body);
                Optional<String> vanityId = extractProperty(mapper.readTree(body), property);
                if (vanityId.isPresent()) {
                    LOGGER.info("Resolved vanity id property '{}' for asset {}: '{}'", property, assetId, vanityId.get());
                } else {
                    LOGGER.info("Property '{}' not present (or empty) in Dynamic Media metadata for asset {}", property, assetId);
                }
                return vanityId;
            }
        } catch (IOException e) {
            LOGGER.warn("Dynamic Media metadata request for " + assetId + " failed", e);
            return Optional.empty();
        } finally {
            closeQuietly(httpClient);
        }
    }

    private Optional<String> extractProperty(JsonNode root, String property) {
        JsonNode value = root.get(property);
        String foundAt = "top-level";
        if (value == null || value.isMissingNode()) {
            // Confirmed shape of /adobe/assets/{id}/metadata: {assetId, repositoryMetadata, assetMetadata:{...}} -
            // custom and standard properties (dc:description, now:vanityId, etc.) live under assetMetadata.
            value = root.path(ASSET_METADATA_KEY).get(property);
            foundAt = ASSET_METADATA_KEY;
        }
        if (value == null || value.isMissingNode()) {
            // Not seen from this endpoint in practice, but kept as a fallback in case a differently-shaped
            // response (e.g. a HAL resource from another DM API) nests properties here instead.
            value = root.path(EMBEDDED_METADATA_KEY).get(property);
            foundAt = "_embedded";
        }
        if (value == null || value.isMissingNode() || value.isNull()) {
            LOGGER.debug("Property '{}' not found at top-level, in {}, or in _embedded metadata", property, ASSET_METADATA_KEY);
            return Optional.empty();
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        if (StringUtils.isBlank(text)) {
            return Optional.empty();
        }
        LOGGER.debug("Property '{}' found in {} metadata block: '{}'", property, foundAt, text);
        return Optional.of(text);
    }

    private void closeQuietly(CloseableHttpClient client) {
        try {
            client.close();
        } catch (IOException e) {
            LOGGER.warn("Failed to close Dynamic Media metadata HTTP client", e);
        }
    }
}
