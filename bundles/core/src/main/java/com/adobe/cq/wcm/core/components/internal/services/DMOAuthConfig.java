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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Core Components Dynamic Media OAuth Service",
    description = "Configuration for the IMS OAuth Server-to-Server credential used to resolve vanity asset ids " +
        "via authenticated Dynamic Media metadata calls."
)
public @interface DMOAuthConfig {

    String DEFAULT_TOKEN_ENDPOINT = "https://ims-na1.adobelogin.com/ims/token/v3";
    int DEFAULT_CONNECTION_TIMEOUT = 2000;
    int DEFAULT_SOCKET_TIMEOUT = 10000;

    @AttributeDefinition(
        name = "Client ID",
        description = "IMS OAuth Server-to-Server client ID (API key) from the Adobe Developer Console credential."
    )
    String clientId();

    @AttributeDefinition(
        name = "Client Secret",
        description = "IMS OAuth Server-to-Server client secret. Encrypted at rest by AEM's OSGi config storage.",
        type = AttributeType.PASSWORD
    )
    String clientSecret();

    @AttributeDefinition(
        name = "Scope",
        description = "OAuth scope value, copied exactly from the Developer Console credential (comma-separated)."
    )
    String scope();

    @AttributeDefinition(
        name = "Token Endpoint",
        description = "IMS OAuth token endpoint."
    )
    String tokenEndpoint() default DEFAULT_TOKEN_ENDPOINT;

    @AttributeDefinition(
        name = "Connection Timeout",
        description = "Time (ms) to establish the connection with the IMS token endpoint."
    )
    int connectionTimeout() default DEFAULT_CONNECTION_TIMEOUT;

    @AttributeDefinition(
        name = "Socket Timeout",
        description = "Time (ms) waiting for data after establishing the connection."
    )
    int socketTimeout() default DEFAULT_SOCKET_TIMEOUT;
}
