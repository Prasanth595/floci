package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebResponses.View;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Lambda Web Functions control plane ("Lambda Web", apiVersion 2025-03-07, rest-json, signs as
 * {@code lambda}). Routes live under the {@code /2025-03-07} prefix. Request members are camelCase
 * except on the tag and resource-policy operations, whose bodies carry {@code Tags}, {@code Policy}
 * and {@code RevisionId}. Tags ride {@code /2025-03-07/tags}, separate from the classic
 * {@code /2017-03-31/tags} routes.
 */
@Path("/2025-03-07")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class LambdaWebController {

    private final LambdaWebService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaWebController(LambdaWebService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/web-functions")
    public Response createWebFunction(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        WebFunction fn = service.createFunction(region, regionResolver.getAccountId(), parse(body));
        WebFunctionRevision revision = fn.revisions.values().iterator().next();
        WebFunctionEndpoint endpoint = fn.endpoints.values().iterator().next();
        return Response.status(202).entity(LambdaWebResponses.createdFunction(fn, revision, endpoint)).build();
    }

    @POST
    @Path("/web-functions")
    public Response listWebFunctions(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaWebService.Page<WebFunction> page = service.listFunctions(region, parse(body));
        return Response.ok(LambdaWebResponses.list("functions",
                page.items().stream().map(LambdaWebResponses::function).toList(), page.nextToken())).build();
    }

    @GET
    @Path("/web-functions/{functionName}")
    public Response getWebFunction(@Context HttpHeaders headers, @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(LambdaWebResponses.function(service.getFunction(region, functionName))).build();
    }

    @DELETE
    @Path("/web-functions/{functionName}")
    public Response deleteWebFunction(@Context HttpHeaders headers, @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteFunction(region, functionName);
        return Response.noContent().build();
    }

    @POST
    @Path("/web-functions/{functionName}/revisions")
    public Response createWebFunctionRevision(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName, String body) {
        String region = regionResolver.resolveRegion(headers);
        WebFunctionRevision revision = service.createRevision(region, functionName, parse(body));
        return Response.status(202).entity(LambdaWebResponses.revision(revision, View.CREATED)).build();
    }

    @POST
    @Path("/web-functions/{functionName}/list-revisions")
    public Response listWebFunctionRevisions(@Context HttpHeaders headers,
                                             @PathParam("functionName") String functionName, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaWebService.Page<WebFunctionRevision> page = service.listRevisions(region, functionName, parse(body));
        return Response.ok(LambdaWebResponses.list("revisions",
                page.items().stream().map(r -> LambdaWebResponses.revisionSummary(r, View.STORED)).toList(),
                page.nextToken())).build();
    }

    @GET
    @Path("/web-functions/{functionName}/revisions/{revisionId}")
    public Response getWebFunctionRevision(@Context HttpHeaders headers,
                                           @PathParam("functionName") String functionName,
                                           @PathParam("revisionId") String revisionId) {
        String region = regionResolver.resolveRegion(headers);
        WebFunctionRevision revision = service.getRevision(region, functionName, revisionId);
        return Response.ok(LambdaWebResponses.revision(revision, View.STORED)).build();
    }

    @DELETE
    @Path("/web-functions/{functionName}/revisions/{revisionId}")
    public Response deleteWebFunctionRevision(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName,
                                              @PathParam("revisionId") String revisionId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRevision(region, functionName, revisionId);
        return Response.noContent().build();
    }

    @PUT
    @Path("/web-functions/{functionName}/endpoints")
    public Response createWebFunctionEndpoint(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName, String body) {
        String region = regionResolver.resolveRegion(headers);
        WebFunctionEndpoint endpoint = service.createEndpoint(region, regionResolver.getAccountId(), functionName,
                parse(body));
        return Response.status(202).entity(LambdaWebResponses.endpoint(endpoint, endpoint, View.CREATED)).build();
    }

    @POST
    @Path("/web-functions/{functionName}/list-endpoints")
    public Response listWebFunctionEndpoints(@Context HttpHeaders headers,
                                             @PathParam("functionName") String functionName, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaWebService.Page<WebFunctionEndpoint> page = service.listEndpoints(region, functionName, parse(body));
        return Response.ok(LambdaWebResponses.list("endpoints",
                page.items().stream().map(e -> LambdaWebResponses.endpointSummary(e, View.STORED)).toList(),
                page.nextToken())).build();
    }

    @GET
    @Path("/web-functions/{functionName}/endpoints/{endpointName}")
    public Response getWebFunctionEndpoint(@Context HttpHeaders headers,
                                           @PathParam("functionName") String functionName,
                                           @PathParam("endpointName") String endpointName) {
        String region = regionResolver.resolveRegion(headers);
        WebFunctionEndpoint endpoint = service.getEndpoint(region, functionName, endpointName);
        return Response.ok(LambdaWebResponses.endpoint(endpoint, endpoint, View.STORED)).build();
    }

    @PATCH
    @Path("/web-functions/{functionName}/endpoints/{endpointName}")
    public Response updateWebFunctionEndpoint(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName,
                                              @PathParam("endpointName") String endpointName, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaWebService.EndpointUpdate update = service.updateEndpoint(region, functionName, endpointName, parse(body));
        return Response.status(202)
                .entity(LambdaWebResponses.endpoint(update.after(), update.before(), View.UPDATED)).build();
    }

    @DELETE
    @Path("/web-functions/{functionName}/endpoints/{endpointName}")
    public Response deleteWebFunctionEndpoint(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName,
                                              @PathParam("endpointName") String endpointName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteEndpoint(region, functionName, endpointName);
        return Response.noContent().build();
    }

    @GET
    @Path("/web-account-settings")
    public Response getWebAccountSettings(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(LambdaWebResponses.accountSettings(service.accountSettings(region))).build();
    }

    @GET
    @Path("/tags/{resource: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("resource") String resource) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(Map.of("Tags", service.listTags(region, resource))).build();
    }

    @POST
    @Path("/tags/{resource: .+}")
    public Response tagResource(@Context HttpHeaders headers, @PathParam("resource") String resource, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        JsonNode tags = request.get("Tags");
        if (tags == null || tags.isNull()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'tags' failed to satisfy constraint: Member must not be null",
                    400);
        }
        Map<String, String> map = new LinkedHashMap<>();
        tags.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
        service.tagResource(region, resource, map);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/tags/{resource: .+}")
    public Response untagResource(@Context HttpHeaders headers, @PathParam("resource") String resource,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        service.untagResource(region, resource, tagKeys);
        return Response.noContent().build();
    }

    @PUT
    @Path("/resource-policy/{resourceArn: .+}")
    public Response putResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = parse(body);
        LambdaWebService.ResourcePolicy policy = service.putResourcePolicy(region, resourceArn,
                text(request, "Policy"), text(request, "RevisionId"));
        return Response.ok(LambdaWebResponses.policy(policy)).build();
    }

    @GET
    @Path("/resource-policy/{resourceArn: .+}")
    public Response getResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(LambdaWebResponses.policy(service.getResourcePolicy(region, resourceArn))).build();
    }

    @DELETE
    @Path("/resource-policy/{resourceArn: .+}")
    public Response deleteResourcePolicy(@Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn,
                                         @QueryParam("RevisionId") String revisionId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteResourcePolicy(region, resourceArn, revisionId);
        return Response.noContent().build();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
