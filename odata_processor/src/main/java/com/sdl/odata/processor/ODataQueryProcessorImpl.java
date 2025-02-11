/**
 * Copyright (c) 2014-2024 All Rights Reserved by the RWS Group for and on behalf of its affiliates and subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sdl.odata.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdl.odata.api.ODataBadRequestException;
import com.sdl.odata.api.ODataException;
import com.sdl.odata.api.edm.model.EntityDataModel;
import com.sdl.odata.api.parser.MetadataUri;
import com.sdl.odata.api.parser.ODataUri;
import com.sdl.odata.api.parser.ODataUriUtil;
import com.sdl.odata.api.parser.RelativeUri;
import com.sdl.odata.api.parser.ServiceRootUri;
import com.sdl.odata.api.parser.TargetType;
import com.sdl.odata.api.processor.ODataQueryProcessor;
import com.sdl.odata.api.processor.ProcessorResult;
import com.sdl.odata.api.processor.datasource.ODataDataSourceException;
import com.sdl.odata.api.processor.datasource.ODataEntityNotFoundException;
import com.sdl.odata.api.processor.datasource.factory.DataSourceFactory;
import com.sdl.odata.api.processor.query.QueryResult;
import com.sdl.odata.api.service.ODataRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import scala.Option;

import java.util.List;

import static com.sdl.odata.api.service.ODataResponse.Status.OK;

import com.looker.rtl.AuthSession;
import com.looker.rtl.ConfigurationProvider;
import com.looker.rtl.Transport;
import com.looker.sdk.ApiSettings;
import com.looker.sdk.LookerSDK;
import com.looker.rtl.SDKResponse;
import io.github.cdimascio.dotenv.Dotenv;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;

/**
 * Implementation of {@code ODataQueryProcessor}.
 */
@Component
public class ODataQueryProcessorImpl implements ODataQueryProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(ODataQueryProcessorImpl.class);

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Override
    public ProcessorResult query(ODataRequestContext requestContext, Object data) throws ODataException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("ODataQueryProcessorImpl.query() for {}, data: {}", requestContext.getRequest(), data);
        }

        ODataUri oDataUri = requestContext.getUri();
        EntityDataModel entityDataModel = requestContext.getEntityDataModel();

        RelativeUri relativeUri = oDataUri.relativeUri();
        if (isMetadataUri(relativeUri) || isServiceRootUri(relativeUri)) {
            return new ProcessorResult(OK, QueryResult.from(entityDataModel));
        }

        Option<TargetType> targetTypeOption = ODataUriUtil.resolveTargetType(oDataUri, entityDataModel);
        if (!targetTypeOption.isDefined()) {
            throw new ODataBadRequestException("The target type could not be determined for this query: " +
                    requestContext.getRequest().getUri());
        }

        TargetType targetType = targetTypeOption.get();

        // Load settings from .env file into system properties
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));

        // Setup the settings from system properties
        ConfigurationProvider settings = ApiSettings.fromMap(new HashMap<>());
        settings.readConfig();
        AuthSession session = new AuthSession(settings, new Transport(settings));
        LookerSDK looker = new LookerSDK(session);

        // Fetch results of the query using query_id and result_format
        String queryId = "703707";
        String resultFormat = "json";
        SDKResponse sdkResponse = looker.run_query(resultFormat, queryId);

        // Handle the SDKResponse
        String lookerResults;
        if (sdkResponse instanceof SDKResponse.SDKSuccessResponse) {
            SDKResponse.SDKSuccessResponse successResponse = (SDKResponse.SDKSuccessResponse) sdkResponse;
            // Unpack the body of a successful SDKResponse as a string
            try {
                lookerResults = successResponse.toString();
            } catch (Exception e) {
                throw new ODataDataSourceException("Error unpacking SDKResponse: " + e.getMessage(), e);
            }
        } else if (sdkResponse instanceof SDKResponse.SDKErrorResponse) {
            SDKResponse.SDKErrorResponse errorResponse = (SDKResponse.SDKErrorResponse) sdkResponse;
            throw new ODataDataSourceException("Error response from Looker: " + errorResponse);
        } else if (sdkResponse instanceof SDKResponse.SDKError) {
            SDKResponse.SDKError error = (SDKResponse.SDKError) sdkResponse;
            throw new ODataDataSourceException("SDK Error: " + error);
        } else {
            throw new IllegalArgumentException("Unknown response type");
        }

        // Parse the JSON string to a List<Map<String, Object>>
        List<Map<String, Object>> parsedResults;
        try {
            parsedResults = new ObjectMapper()
                .readValue(lookerResults, new TypeReference<List<Map<String, Object>>>() { });
        } catch (IOException e) {
            throw new ODataDataSourceException("Error parsing Looker results: " + e.getMessage(), e);
        }

        // Process Looker results
        QueryResult result = QueryResult.from(parsedResults);

        if (targetType.isCollection()) {
            return new ProcessorResult(OK, result);
        } else {
            if (result.getType() != QueryResult.ResultType.COLLECTION) {
                return new ProcessorResult(OK, result);
            }
            List<?> list = (List<?>) result.getData();
            if (list.size() == 0) {
                throw new ODataEntityNotFoundException("Entity not found for this query: " +
                        requestContext.getRequest().getUri());
            } else if (list.size() > 1) {
                throw new ODataDataSourceException("Expected one result, but found multiple for this query: " +
                        requestContext.getRequest().getUri());
            }

            return new ProcessorResult(OK, QueryResult.from(list.get(0)));
        }
    }

    private boolean isMetadataUri(RelativeUri relativeUri) {
        return relativeUri instanceof MetadataUri;
    }

    private boolean isServiceRootUri(RelativeUri relativeUri) {
        return relativeUri instanceof ServiceRootUri;
    }
}
