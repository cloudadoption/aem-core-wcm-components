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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.adobe.cq.ui.wcm.commons.config.NextGenDynamicMediaConfig;
import com.adobe.cq.wcm.core.components.context.CoreComponentTestContext;
import com.adobe.cq.wcm.core.components.internal.services.DMOAuthService;
import com.adobe.cq.wcm.core.components.testing.Utils;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(AemContextExtension.class)
class VanityIdResolverServletTest {

    private static final String TEST_REPOSITORY_ID = "delivery-test.adobeaemcloud.com";
    private static final String TEST_ASSET_ID = "urn:aaid:aem:test-uuid";

    public final AemContext context = CoreComponentTestContext.newAemContext();

    private VanityIdResolverServlet servlet;
    private MockSlingHttpServletRequest request;
    private DMOAuthService mockDmOAuthService;
    private CloseableHttpClient mockHttpClient;
    private CloseableHttpResponse mockResponse;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new VanityIdResolverServlet();
        mockDmOAuthService = mock(DMOAuthService.class);
        when(mockDmOAuthService.getAccessToken()).thenReturn(Optional.of("test-access-token"));
        NextGenDynamicMediaConfig ngdmConfig = mock(NextGenDynamicMediaConfig.class);
        when(ngdmConfig.getRepositoryId()).thenReturn(TEST_REPOSITORY_ID);
        Utils.setInternalState(servlet, "dmOAuthService", mockDmOAuthService);
        Utils.setInternalState(servlet, "nextGenDynamicMediaConfig", ngdmConfig);
        servlet.activate();
        mockHttpClient = mock(CloseableHttpClient.class);
        Utils.setInternalState(servlet, "httpClient", mockHttpClient);
        mockResponse = mock(CloseableHttpResponse.class);

        request = context.request();
    }

    /**
     * Wraps the request in a spy so getResourceResolver() can be overridden per test, without needing a setter
     * (MockSlingHttpServletRequest binds its resolver at construction). Call after all addRequestParameter calls.
     */
    private SlingHttpServletRequest asUser(String userId) {
        ResourceResolver resolver = mock(ResourceResolver.class);
        when(resolver.getUserID()).thenReturn(userId);
        SlingHttpServletRequest spy = Mockito.spy(request);
        Mockito.doReturn(resolver).when(spy).getResourceResolver();
        return spy;
    }

    private void respondWith(int statusCode, String jsonBody) throws IOException {
        StatusLine statusLine = new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, "");
        when(mockResponse.getStatusLine()).thenReturn(statusLine);
        HttpEntity mockEntity = mock(HttpEntity.class);
        when(mockEntity.getContent()).thenReturn(new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8)));
        when(mockResponse.getEntity()).thenReturn(mockEntity);
        when(mockHttpClient.execute(any(HttpUriRequest.class))).thenReturn(mockResponse);
    }

    @Test
    void anonymousUserIsForbidden() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "now:vanityId");

        servlet.doGet(asUser("anonymous"), context.response());

        assertEquals(HttpServletResponse.SC_FORBIDDEN, context.response().getStatus());
    }

    @Test
    void blankAssetIdReturnsBadRequest() throws Exception {
        request.addRequestParameter("property", "now:vanityId");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, context.response().getStatus());
    }

    @Test
    void blankPropertyReturnsBadRequest() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);

        servlet.doGet(asUser("author1"), context.response());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, context.response().getStatus());
    }

    @Test
    void resolvesPropertyFromAssetMetadata() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "now:vanityId");
        respondWith(200, "{\"assetMetadata\":{\"now:vanityId\":\"hash-value\"}}");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals(HttpServletResponse.SC_OK, context.response().getStatus());
        assertEquals("{\"vanityId\":\"hash-value\"}", context.response().getOutputAsString());
    }

    @Test
    void resolvesPropertyFromEmbeddedFallback() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "dc:description");
        respondWith(200, "{\"http://ns.adobe.com/adobecloud/rel/metadata/embedded\":"
            + "{\"dc:description\":\"embedded-value\"}}");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("{\"vanityId\":\"embedded-value\"}", context.response().getOutputAsString());
    }

    @Test
    void propertyNotFoundReturnsEmptyBody() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "dc:description");
        respondWith(200, "{\"assetMetadata\":{}}");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("", context.response().getOutputAsString());
    }

    @Test
    void noAccessTokenReturnsEmptyBody() throws Exception {
        when(mockDmOAuthService.getAccessToken()).thenReturn(Optional.empty());
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "now:vanityId");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("", context.response().getOutputAsString());
        Mockito.verifyNoInteractions(mockHttpClient);
    }

    @Test
    void blankRepositoryIdReturnsEmptyBody() throws Exception {
        NextGenDynamicMediaConfig blankConfig = mock(NextGenDynamicMediaConfig.class);
        when(blankConfig.getRepositoryId()).thenReturn("");
        Utils.setInternalState(servlet, "nextGenDynamicMediaConfig", blankConfig);
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "now:vanityId");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("", context.response().getOutputAsString());
    }

    @Test
    void nonOkMetadataResponseReturnsEmptyBody() throws Exception {
        request.addRequestParameter("assetId", TEST_ASSET_ID);
        request.addRequestParameter("property", "now:vanityId");
        respondWith(403, "{\"error\":\"forbidden\"}");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("", context.response().getOutputAsString());
    }

    @Test
    void malformedAssetIdDoesNotThrow() throws Exception {
        // A space is illegal in a URI - new HttpGet(url) throws IllegalArgumentException, which must degrade
        // to the same empty response as any other failure, not an unhandled exception.
        request.addRequestParameter("assetId", "urn:aaid:aem:bad value");
        request.addRequestParameter("property", "now:vanityId");

        servlet.doGet(asUser("author1"), context.response());

        assertEquals("", context.response().getOutputAsString());
    }
}
