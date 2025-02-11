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
public class ODataQueryProcessorImpl {

    @Override
    public ProcessorResult query(ODataRequestContext requestContext, Object data) throws ODataException {
        LOG.info("Entering query method");
        if (LOG.isTraceEnabled()) {
            LOG.trace("ODataQueryProcessorImpl.query() for {}, data: {}", requestContext.getRequest(), data);
        }

        ODataUri oDataUri = requestContext.getUri();
        EntityDataModel entityDataModel = requestContext.getEntityDataModel();

        RelativeUri relativeUri = oDataUri.relativeUri();
        if (isMetadataUri(relativeUri) || isServiceRootUri(relativeUri)) {
            LOG.info("Query is for metadata or service root URI");
            return new ProcessorResult(OK, QueryResult.from(entityDataModel));
        }

        Option<TargetType> targetTypeOption = ODataUriUtil.resolveTargetType(oDataUri, entityDataModel);
        if (!targetTypeOption.isDefined()) {
            LOG.error("Target type could not be determined for query: {}", requestContext.getRequest().getUri());
            throw new ODataBadRequestException("The target type could not be determined for this query: " +
                    requestContext.getRequest().getUri());
        }

        TargetType targetType = targetTypeOption.get();
        LOG.info("Resolved target type: {}", targetType);

        // Load settings from .env file into system properties
        Dotenv dotenv = Dotenv.load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        LOG.info(".env file loaded into system properties");

        // Setup the settings from system properties
        ConfigurationProvider settings = ApiSettings.fromMap(new HashMap<>());
        settings.readConfig();
        AuthSession session = new AuthSession(settings, new Transport(settings));
        LookerSDK looker = new LookerSDK(session);
        LOG.info("Looker SDK initialized");

        // Fetch results of the query using query_id and result_format
        String queryId = "703707";
        String resultFormat = "json";
        LOG.info("Running Looker query with queryId: {} and resultFormat: {}", queryId, resultFormat);
        SDKResponse sdkResponse = looker.run_query(resultFormat, queryId);

        // Handle the SDKResponse
        String lookerResults;
        if (sdkResponse instanceof SDKResponse.SDKSuccessResponse) {
            SDKResponse.SDKSuccessResponse successResponse = (SDKResponse.SDKSuccessResponse) sdkResponse;
            // Unpack the body of a successful SDKResponse as a string
            try {
                lookerResults = successResponse.toString();
                LOG.info("Looker query successful, results obtained");
            } catch (Exception e) {
                LOG.error("Error unpacking SDKResponse: {}", e.getMessage(), e);
                throw new ODataDataSourceException("Error unpacking SDKResponse: " + e.getMessage(), e);
            }
        } else if (sdkResponse instanceof SDKResponse.SDKErrorResponse) {
            SDKResponse.SDKErrorResponse errorResponse = (SDKResponse.SDKErrorResponse) sdkResponse;
            LOG.error("Error response from Looker: {}", errorResponse);
            throw new ODataDataSourceException("Error response from Looker: " + errorResponse);
        } else if (sdkResponse instanceof SDKResponse.SDKError) {
            SDKResponse.SDKError error = (SDKResponse.SDKError) sdkResponse;
            LOG.error("SDK Error: {}", error);
            throw new ODataDataSourceException("SDK Error: " + error);
        } else {
            LOG.error("Unknown response type");
            throw new IllegalArgumentException("Unknown response type");
        }

        // Parse the JSON string to a List<Map<String, Object>>
        List<Map<String, Object>> parsedResults;
        try {
            parsedResults = new ObjectMapper()
                .readValue(lookerResults, new TypeReference<List<Map<String, Object>>>() { });
            LOG.info("Looker results parsed successfully");
        } catch (IOException e) {
            LOG.error("Error parsing Looker results: {}", e.getMessage(), e);
            throw new ODataDataSourceException("Error parsing Looker results: " + e.getMessage(), e);
        }

        // Process Looker results
        QueryResult result = QueryResult.from(parsedResults);
        LOG.info("Looker results processed into QueryResult");

        if (targetType.isCollection()) {
            LOG.info("Returning collection result");
            return new ProcessorResult(OK, result);
        } else {
            if (result.getType() != QueryResult.ResultType.COLLECTION) {
                LOG.info("Returning single result");
                return new ProcessorResult(OK, result);
            }
            List<?> list = (List<?>) result.getData();
            if (list.size() == 0) {
                LOG.error("Entity not found for query: {}", requestContext.getRequest().getUri());
                throw new ODataEntityNotFoundException("Entity not found for this query: " +
                        requestContext.getRequest().getUri());
            } else if (list.size() > 1) {
                LOG.error("Expected one result, but found multiple for query: {}", requestContext.getRequest().getUri());
                throw new ODataDataSourceException("Expected one result, but found multiple for this query: " +
                        requestContext.getRequest().getUri());
            }

            LOG.info("Returning single entity result");
            return new ProcessorResult(OK, QueryResult.from(list.get(0)));
        }
    }
    
}
